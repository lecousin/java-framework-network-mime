package net.lecousin.framework.network.mime.entity;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/** Multi-part entity, see RFC 1341. */
public class MultipartEntity extends MimeEntity {
	
	public static final String MAIN_CONTENT_TYPE = "multipart";

	/** Constructor. */
	public MultipartEntity(byte[] boundary, String subType) {
		super(null);
		this.boundary = boundary;
		setHeader(MimeHeaders.CONTENT_TYPE,
			new ParameterizedHeaderValue(MAIN_CONTENT_TYPE + "/" + subType,
				"boundary", new String(boundary, StandardCharsets.US_ASCII)));
	}
	
	/** Constructor. */
	public MultipartEntity(String subType) {
		this(generateBoundary(), subType);
	}
	
	/** From existing headers. */
	public MultipartEntity(MimeEntity parent, MimeHeaders headers) throws MimeException {
		super(parent, headers);
		ParameterizedHeaderValue ct = headers.getContentType();
		if (ct == null)
			throw new MimeException("Missing Content-Type header");
		String s = ct.getParameterIgnoreCase("boundary");
		if (s == null)
			throw new MimeException("No boundary specified in Content-Type header");
		this.boundary = s.getBytes(StandardCharsets.US_ASCII);
		this.partFactory = parent instanceof MultipartEntity ? ((MultipartEntity)parent).partFactory : DefaultMimeEntityFactory.getInstance();
	}
	
	private static int counter = 0;
	private static final Random random = new Random();
	
	protected byte[] boundary;
	protected LinkedList<MimeEntity> parts = new LinkedList<>();
	protected MimeEntityFactory partFactory = null;
	
	public MimeEntityFactory getPartFactory() {
		return partFactory;
	}

	public void setPartFactory(MimeEntityFactory partFactory) {
		this.partFactory = partFactory;
	}

	protected static byte[] generateBoundary() {
		int count;
		long rand;
		synchronized (random) {
			count = counter++;
			rand = random.nextLong();
		}
		long timestamp = System.currentTimeMillis();
		byte[] boundary = new byte[25];
		boundary[0] = 'l';
		boundary[1] = 'c';
		boundary[2] = 'm';
		boundary[3] = 'p';
		boundary[4] = '=';
		boundary[5] = '_'; // =_ cannot appear in quoted-printable strings
		boundary[6] = encodeBoundary((int)(timestamp & 0x1F));
		boundary[7] = encodeBoundary((int)((timestamp >> 5) & 0x1F));
		boundary[8] = encodeBoundary((int)((timestamp >> 10) & 0x1F));
		boundary[9] = encodeBoundary((int)((timestamp >> 15) & 0x1F));
		boundary[10] = encodeBoundary((int)((timestamp >> 20) & 0x1F));
		boundary[11] = encodeBoundary((int)((timestamp >> 25) & 0x1F));
		boundary[12] = '/';
		boundary[13] = encodeBoundary(count & 0x1F);
		boundary[14] = encodeBoundary((count >> 5) & 0x1F);
		boundary[15] = encodeBoundary((count >> 10) & 0x1F);
		boundary[16] = encodeBoundary((count >> 15) & 0x1F);
		boundary[17] = '/';
		boundary[18] = encodeBoundary((int)(rand & 0x1F));
		boundary[19] = encodeBoundary((int)((rand >> 5) & 0x1F));
		boundary[20] = encodeBoundary((int)((rand >> 10) & 0x1F));
		boundary[21] = encodeBoundary((int)((rand >> 15) & 0x1F));
		boundary[22] = encodeBoundary((int)((rand >> 20) & 0x1F));
		boundary[23] = encodeBoundary((int)((rand >> 25) & 0x1F));
		boundary[24] = '.';
		return boundary;
	}
	
	private static byte encodeBoundary(int value) {
		if (value < 26) return (byte)('a' + value);
		return (byte)('0' + (value - 26));
	}
	
	public byte[] getBoundary() {
		return boundary;
	}
	
	/** Append a part. */
	public void add(MimeEntity part) {
		parts.add(part);
		part.parent = this;
	}
	
	public List<MimeEntity> getParts() {
		return parts;
	}
	
	/** Return the parts compatible with the given type. */
	@SuppressWarnings("unchecked")
	public <T extends MimeEntity> List<T> getPartsOfType(Class<T> type) {
		LinkedList<T> list = new LinkedList<>();
		for (MimeEntity p : parts)
			if (type.isAssignableFrom(p.getClass()))
				list.add((T)p);
		return list;
	}
	
	@Override
	public AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> createBodyProducer() {
		return new AsyncSupplier<>(new Pair<>(null, new BodyProducer()), null);
	}
	
	/** Producer of body data. */
	public class BodyProducer implements AsyncProducer<ByteBuffer, IOException> {
		
		private boolean boundSent = false;
		private Iterator<MimeEntity> itPart = parts.iterator();
		private MimeEntity currentEntity;
		private boolean headersSent = false;
		private AsyncProducer<ByteBuffer, IOException> bodyProducer;
		private byte[] bound;
		
		/** Constructor. */
		public BodyProducer() {
			bound = new byte[6 + boundary.length];
			bound[0] = bound[boundary.length + 4] = '\r';
			bound[1] = bound[boundary.length + 5] = '\n';
			bound[2] = '-';
			bound[3] = '-';
			System.arraycopy(boundary, 0, bound, 4, boundary.length);
		}
		
		@Override
		public AsyncSupplier<ByteBuffer, IOException> produce() {
			if (currentEntity == null && !itPart.hasNext()) {
				if (boundSent)
					return new AsyncSupplier<>(null, null);
				boundSent = true;
				byte[] finalBoundary = new byte[bound.length + 2];
				System.arraycopy(bound, 0, finalBoundary, 0, bound.length - 2);
				finalBoundary[boundary.length + 4] = '-';
				finalBoundary[boundary.length + 5] = '-';
				finalBoundary[boundary.length + 6] = '\r';
				finalBoundary[boundary.length + 7] = '\n';
				return new AsyncSupplier<>(ByteBuffer.wrap(finalBoundary), null);
			}
			if (currentEntity == null) {
				currentEntity = itPart.next();
				return new AsyncSupplier<>(ByteBuffer.wrap(bound), null);
			}
			if (!headersSent) {
				headersSent = true;
				return new AsyncSupplier<>(ByteBuffer.wrap(currentEntity.getHeaders().generateString().toIso8859Bytes()), null);
			}
			if (bodyProducer == null) {
				AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> body =
					currentEntity.createBodyProducer();
				AsyncSupplier<ByteBuffer, IOException> result = new AsyncSupplier<>();
				body.onDone(pair -> {
					bodyProducer = pair.getValue2();
					bodyProducer.produce().onDone(data -> {
						if (data != null) {
							result.unblockSuccess(data);
							return;
						}
						currentEntity = null;
						headersSent = false;
						boundSent = false;
						bodyProducer = null;
						produce().forward(result);
					}, result);
				}, result);
				return result;
			}
			AsyncSupplier<ByteBuffer, IOException> result = new AsyncSupplier<>();
			bodyProducer.produce().onDone(data -> {
				if (data != null) {
					result.unblockSuccess(data);
					return;
				}
				currentEntity = null;
				headersSent = false;
				boundSent = false;
				bodyProducer = null;
				produce().forward(result);
			}, result);
			return result;
		}
	}
	
	@Override
	public boolean canProduceBodyRange() {
		return false;
	}
	
	@Override
	public Triple<RangeLong, Long, BinaryEntity> createBodyRange(RangeLong range) {
		return null;
	}
	
	@Override
	public AsyncConsumer<ByteBuffer, IOException> createConsumer() {
		return new Parser(partFactory);
	}
	
	private static final byte[] CRLF = new byte[] { '\r', '\n' };
	private static final byte[] SEP = new byte[] { '-', '-' };
	
	/** Parser for multi-part content. */
	public class Parser implements AsyncConsumer<ByteBuffer, IOException> {
		
		/** Constructor. */
		public Parser(MimeEntityFactory entityFactory) {
			if (entityFactory == null) throw new IllegalArgumentException("entityFactory must not be null");
			this.entityFactory = entityFactory;
		}
		
		private MimeEntityFactory entityFactory;
		private boolean firstBoundary = true;
		private int boundaryPos = 2; // first boundary may start without \r\n
		private boolean isFinalBoundary = false;
		private MimeEntity.Parser entityParser;
		private boolean eof = false;
		
		@Override
		public IAsync<IOException> consume(ByteBuffer data) {
			if (eof) {
				ByteArrayCache.getInstance().free(data);
				return new Async<>(true);
			}
			Async<IOException> result = new Async<>();
			consumeData(data, result);
			return result;
		}

		@Override
		public IAsync<IOException> end() {
			if (!eof) {
				EOFException error = new EOFException("Unexpected end in multi-part before final boundary");
				if (entityParser != null)
					entityParser.error(error);
				return new Async<>(error);
			}
			return new Async<>(true);
		}

		@Override
		public void error(IOException error) {
			if (entityParser != null)
				entityParser.error(error);
		}
		
		private void consumeData(ByteBuffer data, Async<IOException> onDone) {
			if (firstBoundary) {
				Boolean found;
				do {
					found = searchBoundary(data);
					if (found != null) break;
					if (!data.hasRemaining()) {
						onDone.unblock();
						return;
					}
				} while (true);
				if (found.booleanValue()) {
					// final found
					firstBoundary = false;
					eof = true;
					data.position(data.position() + data.remaining()); // skip any remaining data
					onDone.unblock();
					return;
				}
				firstBoundary = false;
				entityParser = new MimeEntity.Parser(entityFactory);
			} else if (eof) {
				data.position(data.position() + data.remaining()); // skip any remaining data
				onDone.unblock();
				return;
			}

			do {
				int boundPos = boundaryPos;
				boolean wasFinal = isFinalBoundary;
				int start = data.position();
				Boolean found;
				do {
					found = searchBoundary(data);
				} while (found == null && boundPos == 0 && data.hasRemaining());
				if (found == null) {
					LinkedList<ByteBuffer> buffers = new LinkedList<>();
					if (boundPos > 0)
						addMissedBuffers(boundPos, wasFinal, buffers);
					int end = data.position() - boundaryPos;
					if (!data.hasRemaining()) {
						if (end - start > 0) {
							ByteBuffer subBuffer = data.duplicate();
							subBuffer.position(start);
							subBuffer.limit(end);
							buffers.add(subBuffer.asReadOnlyBuffer());
						}
						IAsync<IOException> push = entityParser.push(buffers);
						push.onDone(onDone);
						return;
					}
					if (end - start > 0) {
						if (data.hasArray()) {
							buffers.add(ByteBuffer.wrap(data.array(), data.arrayOffset() + start, end - start)
								.asReadOnlyBuffer());
						} else {
							byte[] b = new byte[end - start];
							data.position(start);
							data.get(b);
							data.position(end + boundaryPos);
							buffers.add(ByteBuffer.wrap(b));
						}
					}
					IAsync<IOException> push = entityParser.push(buffers);
					if (push.isSuccessful()) continue;
					push.onDone(() -> consumeData(data, onDone), onDone);
					return;
				}
				int end = data.position() - (4 + boundary.length + 2);
				if (found.booleanValue())
					end -= 2;
				if (end - start > 0) {
					boolean isLast = found.booleanValue();
					if (!data.hasRemaining()) {
						// end of data, we can give it directly
						ByteBuffer copy = data.duplicate();
						copy.position(start);
						copy.limit(end);
						entityParser.consume(copy.asReadOnlyBuffer()).onDone(() -> endOfBody(isLast, data, onDone), onDone);
						return;
					}
					// we need a sub-buffer
					ByteBuffer subBuffer;
					if (data.hasArray()) {
						subBuffer = ByteBuffer.wrap(data.array(), data.arrayOffset() + start, end - start).asReadOnlyBuffer();
					} else {
						byte[] b = new byte[end - start];
						int p = data.position();
						data.position(start);
						data.get(b);
						subBuffer = ByteBuffer.wrap(b);
						data.position(p);
					}
					entityParser.consume(subBuffer).onDone(() -> endOfBody(isLast, data, onDone), onDone);
					return;
				}
				endOfBody(found.booleanValue(), data, onDone);
				return;
			} while (data.hasRemaining());
			onDone.unblock();
		}
		
		private void endOfBody(boolean isLast, ByteBuffer data, Async<IOException> onDone) {
			entityParser.end().onDone(() -> {
				parts.add(entityParser.getOutput().getResult());
				if (isLast) {
					// end of multi-part
					eof = true;
					data.position(data.position() + data.remaining()); // skip any remaining data
					onDone.unblock();
					return;
				}
				entityParser = new MimeEntity.Parser(entityFactory);
				if (!data.hasRemaining())
					onDone.unblock();
				else
					Task.cpu("Parse multi-part entity", new Executable.FromRunnable(() -> consumeData(data, onDone))).start();
			}, onDone);
		}
		
		/** return null if no boundary, true for final, false for normal. */
		@SuppressWarnings("java:S3776") // complexity
		private Boolean searchBoundary(ByteBuffer buffer) {
			// a boundary is \r\n--<boundary>[--]\r\n
			while (buffer.hasRemaining()) {
				if (boundaryPos == 0) {
					do {
						if (buffer.get() == '\r') {
							boundaryPos = 1;
							break;
						}
					} while (buffer.hasRemaining());
					continue;
				}
				if (boundaryPos == 1) {
					if (buffer.get() != '\n') {
						buffer.position(buffer.position() - 1);
						boundaryPos = 0;
						return null;
					}
					boundaryPos++;
					continue;
				}
				if (boundaryPos < 4) {
					if (buffer.get() != '-') {
						buffer.position(buffer.position() - 1);
						boundaryPos = 0;
						return null;
					}
					boundaryPos++;
					continue;
				}
				if (boundaryPos < 4 + boundary.length) {
					int len = Math.min(boundary.length - boundaryPos + 4, buffer.remaining());
					boolean valid = true;
					int p = buffer.position();
					for (int i = 0; i < len; ++i) {
						if (buffer.get(p + i) != boundary[i + boundaryPos - 4]) {
							valid = false;
							break;
						}
					}
					if (!valid) {
						boundaryPos = 0;
						return null;
					}
					buffer.position(buffer.position() + len);
					boundaryPos += len;
					continue;
				}
				switch (boundaryPos - 4 - boundary.length) {
				case 0:
					// may be final or not
					switch (buffer.get()) {
					case '\r':
						// normal
						isFinalBoundary = false;
						boundaryPos++;
						break;
					case '-':
						// final
						isFinalBoundary = true;
						boundaryPos++;
						break;
					default:
						// none
						boundaryPos = 0;
						return null;
					}
					break;
				case 1:
					if (isFinalBoundary) {
						// - expected
						if (buffer.get() == '-') {
							boundaryPos++;
						} else {
							buffer.position(buffer.position() - 1);
							boundaryPos = 0;
							return null;
						}
					} else {
						// \n expected
						boundaryPos = 0;
						if (buffer.get() == '\n')
							return Boolean.FALSE; // normal found !
						return null;
					}
					break;
				case 2:
					// \r expected
					if (buffer.get() != '\r') {
						boundaryPos = 0;
						return null;
					}
					boundaryPos++;
					break;
				case 3:
					// \n expected
					boundaryPos = 0;
					if (buffer.get() == '\n')
						return Boolean.TRUE; // final found !
					return null;
				default: break; // not possible
				}
			}
			return null; // not found
		}
		
		private void addMissedBuffers(int pos, boolean wasFinal, List<ByteBuffer> buffers) {
			buffers.add(ByteBuffer.wrap(CRLF, 0, pos >= 2 ? 2 : 1).asReadOnlyBuffer());
			if (pos > 2) {
				buffers.add(ByteBuffer.wrap(SEP, 0, pos >= 4 ? 2 : 1).asReadOnlyBuffer());
				if (pos > 4) {
					buffers.add(ByteBuffer.wrap(boundary, 0, pos >= 4 + boundary.length ? boundary.length : pos - 4)
						.asReadOnlyBuffer());
					if (pos > 4 + boundary.length) {
						if (!wasFinal) {
							buffers.add(ByteBuffer.wrap(CRLF, 0, 1).asReadOnlyBuffer());
						} else {
							buffers.add(ByteBuffer.wrap(SEP, 0, pos >= 4 + boundary.length + 2 ? 2 : 1)
								.asReadOnlyBuffer());
							if (pos > 4 + boundary.length + 2) {
								buffers.add(ByteBuffer.wrap(CRLF, 0, 1).asReadOnlyBuffer());
							}
						}
					}
				}
			}
		}
		
	}

}
