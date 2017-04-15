package com.datastax.simulacron.common.codec;

import com.datastax.oss.protocol.internal.response.result.RawType;
import com.datastax.oss.protocol.internal.response.result.RawType.RawList;
import com.datastax.oss.protocol.internal.response.result.RawType.RawSet;
import com.datastax.oss.protocol.internal.util.Bytes;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.*;
import java.util.UUID;

import static com.datastax.oss.protocol.internal.ProtocolConstants.DataType.*;
import static com.datastax.simulacron.common.codec.CodecUtils.primitive;

public class CqlMapper {

  private static final Logger logger = LoggerFactory.getLogger(CqlMapper.class);

  private final int protocolVersion;

  private static final ByteBuffer TRUE = ByteBuffer.wrap(new byte[] {1});
  private static final ByteBuffer FALSE = ByteBuffer.wrap(new byte[] {0});

  // TODO: Perhaps make these sizebound.
  // TODO: Follow optimizations that java driver makes in codec registry.
  // For resolving by cqlType.
  private final LoadingCache<RawType, Codec<?>> cqlTypeCache =
      Caffeine.newBuilder().build(this::loadCache);

  private final TypeFactory typeFactory = TypeFactory.defaultInstance();

  private static final LoadingCache<Integer, CqlMapper> mapperCache =
      Caffeine.newBuilder().build(CqlMapper::new);

  public static CqlMapper forVersion(int protocolVersion) {
    return mapperCache.get(protocolVersion);
  }

  private CqlMapper(int protocolVersion) {
    this.protocolVersion = protocolVersion;
  }

  private <T> void register(Codec<T> codec) {
    cqlTypeCache.put(codec.getCqlType(), codec);
  }

  @SuppressWarnings("unchecked")
  private Codec<?> loadCache(RawType key) {
    if (key instanceof RawSet) {
      RawSet s = (RawSet) key;
      Codec<?> elementCodec = cqlTypeCache.get(s.elementType);
      return new SetCodec(elementCodec);
    } else if (key instanceof RawList) {
      RawList l = (RawList) key;
      Codec<?> elementCodec = cqlTypeCache.get(l.elementType);
      return new ListCodec(elementCodec);
    } else {
      // TODO support other collection(ish) codecs, Tuple, Map, UDT.
      logger.warn("Could not resolve codec for {}, returning null instead.", key);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  public <T> Codec<T> codecFor(RawType cqlType) {
    return (Codec<T>) cqlTypeCache.get(cqlType);
  }

  abstract class AbstractCodec<T> implements Codec<T> {

    private final JavaType javaType;
    private final RawType cqlType;
    private final int minProtocolVersion;

    AbstractCodec(Type type, RawType cqlType) {
      this(typeFactory.constructType(type), cqlType, 0);
    }

    AbstractCodec(Type type, RawType cqlType, int minProtocolVersion) {
      this(typeFactory.constructType(type), cqlType, minProtocolVersion, true);
    }

    AbstractCodec(JavaType javaType, RawType cqlType, int minProtocolVersion, boolean register) {
      this.javaType = javaType;
      this.cqlType = cqlType;
      this.minProtocolVersion = minProtocolVersion;
      if (register) {
        // only register if requested and meets protocol version requirement.
        register(this);
      }
    }

    private void checkProtocolVersion() {
      if (minProtocolVersion > protocolVersion) {
        throw new ProtocolVersionException(cqlType, protocolVersion, minProtocolVersion);
      }
    }

    @Override
    public ByteBuffer encode(T input) {
      checkProtocolVersion();
      if (input == null) {
        return null;
      }
      return encodeInternal(input);
    }

    public T decode(ByteBuffer input) {
      checkProtocolVersion();
      return decodeInternal(input);
    }

    abstract ByteBuffer encodeInternal(T input);

    abstract T decodeInternal(ByteBuffer input);

    @Override
    public JavaType getJavaType() {
      return javaType;
    }

    @Override
    public RawType getCqlType() {
      return cqlType;
    }
  }

  class StringCodec extends AbstractCodec<String> {
    private final String charset;

    StringCodec(String charset, RawType cqlType) {
      super(String.class, cqlType);
      this.charset = charset;
    }

    @Override
    ByteBuffer encodeInternal(String input) {
      try {
        return ByteBuffer.wrap(input.getBytes(charset));
      } catch (UnsupportedEncodingException uee) {
        throw new InvalidTypeException("Invalid input for charset " + charset, uee);
      }
    }

    @Override
    public String decodeInternal(ByteBuffer input) {
      if (input == null) {
        return null;
      } else if (input.remaining() == 0) {
        return "";
      } else {
        try {
          return new String(Bytes.getArray(input), charset);
        } catch (UnsupportedEncodingException e) {
          throw new InvalidTypeException("Invalid bytes for charset " + charset, e);
        }
      }
    }
  }

  abstract class CollectionCodec<E, C extends Collection<E>> extends AbstractCodec<C> {

    private final Codec<E> elementCodec;

    CollectionCodec(JavaType javaType, RawType cqlType, Codec<E> elementCodec) {
      super(javaType, cqlType, 1, false);
      this.elementCodec = elementCodec;
    }

    @Override
    ByteBuffer encodeInternal(C input) {
      ByteBuffer[] bbs = new ByteBuffer[input.size()];
      int i = 0;
      for (E elt : input) {
        if (elt == null) {
          throw new NullPointerException("Collection elements cannot be null");
        }
        ByteBuffer bb;
        try {
          bb = elementCodec.encode(elt);
        } catch (ClassCastException e) {
          throw new InvalidTypeException("Invalid type for element", e);
        }
        bbs[i++] = bb;
      }
      return pack(bbs);
    }

    @Override
    C decodeInternal(ByteBuffer input) {
      if (input == null || input.remaining() == 0) return newInstance(0);
      try {
        ByteBuffer i = input.duplicate();
        int size = readSize(i);
        C coll = newInstance(size);
        for (int pos = 0; pos < size; pos++) {
          ByteBuffer databb = readValue(i);
          coll.add(elementCodec.decode(databb));
        }
        return coll;
      } catch (BufferUnderflowException e) {
        throw new InvalidTypeException("Not enough bytes to deserialize collection", e);
      }
    }

    protected abstract C newInstance(int size);
  }

  class SetCodec<E> extends CollectionCodec<E, Set<E>> {

    SetCodec(Codec<E> elementCodec) {
      super(
          typeFactory.constructCollectionLikeType(Set.class, elementCodec.getJavaType()),
          new RawSet(elementCodec.getCqlType()),
          elementCodec);
    }

    @Override
    protected Set<E> newInstance(int size) {
      return new LinkedHashSet<>(size);
    }
  }

  class ListCodec<E> extends CollectionCodec<E, List<E>> {

    ListCodec(Codec<E> elementCodec) {
      super(
          typeFactory.constructCollectionLikeType(List.class, elementCodec.getJavaType()),
          new RawList(elementCodec.getCqlType()),
          elementCodec);
    }

    @Override
    protected List<E> newInstance(int size) {
      return new ArrayList<>(size);
    }
  }

  class LongCodec extends AbstractCodec<Long> {

    LongCodec(RawType cqlType) {
      this(cqlType, 0);
    }

    LongCodec(RawType cqlType, int minProtocolVersion) {
      super(Long.class, cqlType, minProtocolVersion);
    }

    @Override
    ByteBuffer encodeInternal(Long input) {
      ByteBuffer bb = ByteBuffer.allocate(8);
      bb.putLong(0, input);
      return bb;
    }

    @Override
    Long decodeInternal(ByteBuffer input) {
      if (input == null || input.remaining() == 0) {
        return 0L;
      } else if (input.remaining() != 8) {
        throw new InvalidTypeException(
            "Invalid 64-bits long value, expecting 8 bytes but got " + input.remaining());
      } else {
        return input.getLong(input.position());
      }
    }
  }

  class UUIDCodec extends AbstractCodec<UUID> {

    UUIDCodec(RawType cqlType) {
      super(UUID.class, cqlType);
    }

    @Override
    ByteBuffer encodeInternal(UUID input) {
      ByteBuffer buf = ByteBuffer.allocate(16);
      buf.putLong(0, input.getMostSignificantBits());
      buf.putLong(8, input.getLeastSignificantBits());
      return buf;
    }

    @Override
    UUID decodeInternal(ByteBuffer input) {
      return input == null || input.remaining() == 0
          ? null
          : new UUID(input.getLong(input.position()), input.getLong(input.position() + 8));
    }
  }

  public final Codec<String> ascii = new StringCodec("US-ASCII", primitive(ASCII));

  public final Codec<Long> bigint = new LongCodec(primitive(BIGINT));

  public final Codec<ByteBuffer> blob =
      new AbstractCodec<ByteBuffer>(ByteBuffer.class, primitive(BLOB)) {

        @Override
        ByteBuffer encodeInternal(ByteBuffer input) {
          return input.duplicate();
        }

        @Override
        ByteBuffer decodeInternal(ByteBuffer input) {
          return encode(input);
        }
      };

  public final Codec<Boolean> bool =
      new AbstractCodec<Boolean>(Boolean.class, primitive(BOOLEAN)) {

        @Override
        ByteBuffer encodeInternal(Boolean input) {
          return input ? TRUE.duplicate() : FALSE.duplicate();
        }

        @Override
        Boolean decodeInternal(ByteBuffer input) {
          if (input == null || input.remaining() == 0) {
            return false;
          } else if (input.remaining() != 1) {
            throw new InvalidTypeException(
                "Invalid boolean value, expecting 1 byte but got " + input.remaining());
          } else {
            return input.get(input.position()) != 0;
          }
        }
      };

  public final Codec<Long> counter = new LongCodec(primitive(COUNTER));

  public final Codec<BigDecimal> decimal =
      new AbstractCodec<BigDecimal>(BigDecimal.class, primitive(DECIMAL)) {
        @Override
        ByteBuffer encodeInternal(BigDecimal input) {
          BigInteger bi = input.unscaledValue();
          int scale = input.scale();
          byte[] bibytes = bi.toByteArray();

          ByteBuffer bytes = ByteBuffer.allocate(4 + bibytes.length);
          bytes.putInt(scale);
          bytes.put(bibytes);
          bytes.rewind();
          return bytes;
        }

        @Override
        BigDecimal decodeInternal(ByteBuffer input) {
          if (input == null || input.remaining() == 0) return null;
          if (input.remaining() < 4)
            throw new InvalidTypeException(
                "Invalid decimal value, expecting at least 4 bytes but got " + input.remaining());

          input = input.duplicate();
          int scale = input.getInt();
          byte[] bibytes = new byte[input.remaining()];
          input.get(bibytes);

          BigInteger bi = new BigInteger(bibytes);
          return new BigDecimal(bi, scale);
        }
      };

  public final Codec<Double> cdouble =
      new AbstractCodec<Double>(Double.class, primitive(DOUBLE)) {

        @Override
        ByteBuffer encodeInternal(Double input) {
          ByteBuffer bb = ByteBuffer.allocate(8);
          bb.putDouble(0, input);
          return bb;
        }

        @Override
        Double decodeInternal(ByteBuffer input) {
          if (input == null || input.remaining() == 0) return 0.0;
          if (input.remaining() != 8)
            throw new InvalidTypeException(
                "Invalid 64-bits double value, expecting 8 bytes but got " + input.remaining());

          return input.getDouble(input.position());
        }
      };

  public final Codec<Float> cfloat =
      new AbstractCodec<Float>(Float.class, primitive(FLOAT)) {
        @Override
        ByteBuffer encodeInternal(Float input) {
          ByteBuffer bb = ByteBuffer.allocate(4);
          bb.putFloat(0, input);
          return bb;
        }

        @Override
        Float decodeInternal(ByteBuffer input) {
          if (input == null || input.remaining() == 0) return 0.0f;
          if (input.remaining() != 4)
            throw new InvalidTypeException(
                "Invalid 32-bits float value, expecting 4 bytes but got " + input.remaining());

          return input.getFloat(input.position());
        }
      };

  public final Codec<Integer> cint =
      new AbstractCodec<Integer>(Integer.class, primitive(INT)) {

        @Override
        ByteBuffer encodeInternal(Integer input) {
          ByteBuffer bb = ByteBuffer.allocate(4);
          bb.putInt(0, input);
          return bb;
        }

        @Override
        Integer decodeInternal(ByteBuffer input) {
          if (input == null || input.remaining() == 0) return 0;
          if (input.remaining() != 4)
            throw new InvalidTypeException(
                "Invalid 32-bits integer value, expecting 4 bytes but got " + input.remaining());

          return input.getInt(input.position());
        }
      };

  public final Codec<Date> timestamp =
      new AbstractCodec<Date>(Date.class, primitive(TIMESTAMP)) {
        @Override
        ByteBuffer encodeInternal(Date input) {
          return input == null ? null : bigint.encode(input.getTime());
        }

        @Override
        Date decodeInternal(ByteBuffer input) {
          return input == null || input.remaining() == 0 ? null : new Date(bigint.decode(input));
        }
      };

  public final Codec<UUID> uuid = new UUIDCodec(primitive(UUID));

  public final Codec<String> varchar = new StringCodec("UTF-8", primitive(VARCHAR));

  public final Codec<BigInteger> varint =
      new AbstractCodec<BigInteger>(BigInteger.class, primitive(VARINT)) {
        @Override
        ByteBuffer encodeInternal(BigInteger input) {
          return ByteBuffer.wrap(input.toByteArray());
        }

        @Override
        BigInteger decodeInternal(ByteBuffer input) {
          return input == null || input.remaining() == 0
              ? null
              : new BigInteger(Bytes.getArray(input));
        }
      };

  public final Codec<UUID> timeuuid = new UUIDCodec(primitive(TIMEUUID));

  public final Codec<InetAddress> inet =
      new AbstractCodec<InetAddress>(InetAddress.class, primitive(INET)) {

        @Override
        ByteBuffer encodeInternal(InetAddress input) {
          return ByteBuffer.wrap(input.getAddress());
        }

        @Override
        InetAddress decodeInternal(ByteBuffer input) {
          if (input == null || input.remaining() == 0) return null;
          try {
            return InetAddress.getByAddress(Bytes.getArray(input));
          } catch (UnknownHostException e) {
            throw new InvalidTypeException(
                "Invalid bytes for inet value, got " + input.remaining() + " bytes", e);
          }
        }
      };

  public final Codec<LocalDate> date =
      new AbstractCodec<LocalDate>(LocalDate.class, primitive(DATE), 4) {
        @Override
        ByteBuffer encodeInternal(LocalDate input) {
          int unsigned = (int) input.toEpochDay() - Integer.MIN_VALUE;
          return cint.encode(unsigned);
        }

        @Override
        LocalDate decodeInternal(ByteBuffer input) {
          if (input == null || input.remaining() == 0) return null;
          int unsigned = cint.decode(input);
          int signed = unsigned + Integer.MIN_VALUE;
          return LocalDate.ofEpochDay(signed);
        }
      };

  public final Codec<Long> time = new LongCodec(primitive(TIME), 4);

  public final Codec<Short> smallint =
      new AbstractCodec<Short>(Short.class, primitive(SMALLINT), 4) {

        @Override
        ByteBuffer encodeInternal(Short input) {
          ByteBuffer bb = ByteBuffer.allocate(2);
          bb.putShort(0, input);
          return bb;
        }

        @Override
        Short decodeInternal(ByteBuffer input) {
          if (input == null || input.remaining() == 0) return 0;
          if (input.remaining() != 2)
            throw new InvalidTypeException(
                "Invalid 16-bits integer value, expecting 2 bytes but got " + input.remaining());

          return input.getShort(input.position());
        }
      };

  public final Codec<Byte> tinyint =
      new AbstractCodec<Byte>(Byte.class, primitive(TINYINT), 4) {

        @Override
        ByteBuffer encodeInternal(Byte input) {
          ByteBuffer bb = ByteBuffer.allocate(1);
          bb.put(0, input);
          return bb;
        }

        @Override
        Byte decodeInternal(ByteBuffer input) {
          if (input == null || input.remaining() == 0) return 0;
          if (input.remaining() != 1)
            throw new InvalidTypeException(
                "Invalid 8-bits integer value, expecting 1 byte but got " + input.remaining());

          return input.get(input.position());
        }
      };

  private ByteBuffer pack(ByteBuffer[] buffers) {
    int size = 0;
    for (ByteBuffer bb : buffers) {
      int elemSize = sizeOfValue(bb);
      size += elemSize;
    }
    ByteBuffer result = ByteBuffer.allocate(sizeOfCollectionSize() + size);
    writeSize(result, buffers.length);
    for (ByteBuffer bb : buffers) {
      writeValue(result, bb);
    }
    return (ByteBuffer) result.flip();
  }

  private int sizeOfValue(ByteBuffer value) {
    return value == null ? 4 : 4 + value.remaining();
  }

  private int sizeOfCollectionSize() {
    return 4;
  }

  private int readSize(ByteBuffer input) {
    return input.getInt();
  }

  private void writeSize(ByteBuffer output, int size) {
    output.putInt(size);
  }

  private ByteBuffer readValue(ByteBuffer input) {
    int size = readSize(input);
    return size < 0 ? null : readBytes(input, size);
  }

  private ByteBuffer readBytes(ByteBuffer bb, int length) {
    ByteBuffer copy = bb.duplicate();
    copy.limit(copy.position() + length);
    bb.position(bb.position() + length);
    return copy;
  }

  private void writeValue(ByteBuffer output, ByteBuffer value) {
    if (value == null) {
      output.putInt(-1);
    } else {
      output.putInt(value.remaining());
      output.put(value.duplicate());
    }
  }
}