package net.lecousin.framework.network.mime.entity;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.LinkedIO;
import net.lecousin.framework.io.SubIO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.buffering.SimpleBufferedReadable;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.MimeUtil;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;

/** Multi-part entity, see RFC 1341. */
public class MultipartEntity extends MimeEntity {

	/** Constructor. */
	@SuppressFBWarnings("EI_EXPOSE_REP2")
	public MultipartEntity(byte[] boundary, String subType) {
		this.boundary = boundary;
		setHeader(CONTENT_TYPE,
			new ParameterizedHeaderValue("multipart/" + subType,
				"boundary", new String(boundary, StandardCharsets.US_ASCII)));
	}
	
	/** Constructor. */
	public MultipartEntity(String subType) {
		this(generateBoundary(), subType);
	}
	
	protected MultipartEntity(MimeMessage mime) throws Exception {
		super(mime);
		ParameterizedHeaderValue ct = mime.getContentType();
		if (ct == null)
			throw new Exception("Missing Content-Type header");
		String s = ct.getParameterIgnoreCase("boundary");
		if (s == null)
			throw new Exception("No boundary specified in Content-Type header");
		this.boundary = s.getBytes(StandardCharsets.US_ASCII);
	}
	
	@SuppressWarnings("resource")
	public static AsyncWork<MultipartEntity, Exception> from(MimeMessage mime) {
		MultipartEntity entity;
		try { entity = new MultipartEntity(mime); }
		catch (Exception e) { return new AsyncWork<>(null, e); }
		
		IO.Readable body = mime.getBodyReceivedAsInput();
		if (body == null)
			return new AsyncWork<>(entity, null);
		SynchronizationPoint<IOException> parse = entity.parse(body);
		AsyncWork<MultipartEntity, Exception> result = new AsyncWork<>();
		parse.listenInlineSP(() -> { result.unblockSuccess(entity); }, result);
		parse.listenInline(() -> { body.closeAsync(); });
		return result;
	}

	private static int counter = 0;
	private static final Random random = new Random();
	
	protected byte[] boundary;
	protected LinkedList<MimeMessage> parts = new LinkedList<>();
	
	protected static byte[] generateBoundary() {
		int count;
		long rand;
		synchronized (random) {
			count = counter++;
			rand = random.nextLong();
		}
		long timestamp = System.currentTimeMillis();
		byte[] boundary = new byte[18];
		boundary[0] = '-';
		boundary[1] = '-';
		boundary[2] = encodeBoundary((int)(timestamp & 0x1F));
		boundary[3] = encodeBoundary((int)((timestamp >> 5) & 0x1F));
		boundary[4] = encodeBoundary((int)((timestamp >> 10) & 0x1F));
		boundary[5] = encodeBoundary((int)((timestamp >> 15) & 0x1F));
		boundary[6] = encodeBoundary((int)((timestamp >> 20) & 0x1F));
		boundary[7] = encodeBoundary((int)((timestamp >> 25) & 0x1F));
		boundary[8] = encodeBoundary(count & 0x1F);
		boundary[9] = encodeBoundary((count >> 5) & 0x1F);
		boundary[10] = encodeBoundary((count >> 10) & 0x1F);
		boundary[11] = encodeBoundary((count >> 15) & 0x1F);
		boundary[12] = encodeBoundary((int)(rand & 0x1F));
		boundary[13] = encodeBoundary((int)((rand >> 5) & 0x1F));
		boundary[14] = encodeBoundary((int)((rand >> 10) & 0x1F));
		boundary[15] = encodeBoundary((int)((rand >> 15) & 0x1F));
		boundary[16] = encodeBoundary((int)((rand >> 20) & 0x1F));
		boundary[17] = encodeBoundary((int)((rand >> 25) & 0x1F));
		return boundary;
	}
	
	private static byte encodeBoundary(int value) {
		if (value < 26) return (byte)('a' + value);
		return (byte)('0' + (value - 26));
	}
	
	@SuppressFBWarnings("EI_EXPOSE_REP")
	public byte[] getBoundary() {
		return boundary;
	}
	
	/** Append a part. */
	public void add(MimeMessage part) {
		parts.add(part);
	}
	
	public List<MimeMessage> getParts() {
		return parts;
	}
	
	/** Return the parts compatible with the given type. */
	@SuppressWarnings("unchecked")
	public <T extends MimeMessage> List<T> getPartsOfType(Class<T> type) {
		LinkedList<T> list = new LinkedList<>();
		for (MimeMessage p : parts)
			if (type.isAssignableFrom(p.getClass()))
				list.add((T)p);
		return list;
	}
	
	@SuppressWarnings("resource")
	@Override
	public IO.Readable getBodyToSend() {
		byte[] bound = new byte[6 + boundary.length];
		bound[0] = bound[boundary.length + 4] = '\r';
		bound[1] = bound[boundary.length + 5] = '\n';
		bound[2] = '-';
		bound[3] = '-';
		System.arraycopy(boundary, 0, bound, 4, boundary.length);
		ByteArrayIO boundaryIO = new ByteArrayIO(bound, "multipart boundary");
		IO.Readable[] ios = new IO.Readable[parts.size() * 2 + 1];
		int i = 0;
		boolean allKnownSize = true;
		for (MimeMessage p : parts) {
			ios[i++] = new SubIO.Readable.Seekable(boundaryIO, 0, bound.length, "Multipart boundary", false);
			IO.Readable io = p.getReadableStream();
			ios[i++] = io;
			if (!(io instanceof IO.KnownSize))
				allKnownSize = false;
		}
		byte[] finalBoundary = new byte[6 + boundary.length];
		finalBoundary[0] = '\r';
		finalBoundary[1] = '\n';
		finalBoundary[2] = finalBoundary[boundary.length + 4] = '-';
		finalBoundary[3] = finalBoundary[boundary.length + 5] = '-';
		System.arraycopy(boundary, 0, finalBoundary, 4, boundary.length);
		ios[i] = new ByteArrayIO(finalBoundary, finalBoundary.length, "multipart ending boundary");
		if (allKnownSize)
			return new LinkedIO.Readable.DeterminedSize("MIME form-data", ios);
		return new LinkedIO.Readable("MIME form-data", ios);
	}
	
	/** Parse the given content. */
	@SuppressWarnings("resource")
	public SynchronizationPoint<IOException> parse(IO.Readable content) {
		IO.Readable.Buffered bio;
		if (content instanceof IO.Readable.Buffered)
			bio = (IO.Readable.Buffered)content;
		else
			bio = new SimpleBufferedReadable(content, 8192);
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
		Parser parser = new Parser(bio, sp);
		parser.nextBuffer();
		return sp;
	}
	
	protected AsyncWork<MimeMessage, IOException> createPart(List<MimeHeader> headers, IOInMemoryOrFile body) {
		MimeMessage mime = new MimeMessage();
		mime.getHeaders().addAll(headers);
		mime.setBodyToSend(body);
		return new AsyncWork<>(mime, null);
	}
			
	private class Parser {
		
		public Parser(IO.Readable.Buffered io, SynchronizationPoint<IOException> sp) {
			this.io = io;
			this.sp = sp;
		}
		
		private IO.Readable.Buffered io;
		private SynchronizationPoint<IOException> sp;
		private byte[] boundaryRead = null;
		private int boundaryPos = 0;
		private boolean firstBoundary = true;
		private MimeUtil.HeadersLinesReceiver header = null;
		private StringBuilder headerLine = null;
		private IOInMemoryOrFile body = null;
		private byte[] bodyBuffer = new byte[1024];
		private int bodyBufferPos = 0;
		
		public void nextBuffer() {
			io.readNextBufferAsync().listenInline(
				(buffer) -> { parse(buffer); },
				sp
			);
		}
		
		private void parse(ByteBuffer buffer) {
			if (buffer == null) {
				sp.error(new EOFException("Unexpected end of multipart content"));
				return;
			}
			new Task.Cpu<Void, NoException>("Parsing multipart content", io.getPriority()) {
				@Override
				public Void run() {
					while (buffer.hasRemaining()) {
						byte b = buffer.get();
						if (header != null && body == null) {
							// reading MIME header
							if (b == '\n') {
								String line;
								if (headerLine.length() > 0 && headerLine.charAt(headerLine.length() - 1) == '\r')
									line = headerLine.substring(0, headerLine.length() - 1);
								else
									line = headerLine.toString();
								try { header.newLine(line); }
								catch (Exception e) {
									sp.error(IO.error(e));
									return null;
								}
								if (line.length() == 0) {
									// end of MIME headers
									headerLine = null;
									body = new IOInMemoryOrFile(8192, io.getPriority(), "Multipart body");
									continue;
								}
								headerLine = new StringBuilder(128);
								continue;
							}
							headerLine.append((char)b);
							continue;
						}
						if (boundaryPos == 0) {
							if (b == '\r') {
								// may be start of a boundary
								if (boundaryRead == null)
									boundaryRead = new byte[4 + boundary.length + 2];
								boundaryRead[0] = b;
								boundaryPos = 1;
								firstBoundary = false;
								continue;
							}
							if (body != null) {
								// body data
								bodyBuffer[bodyBufferPos++] = b;
								if (bodyBufferPos == bodyBuffer.length) {
									body.writeAsync(ByteBuffer.wrap(bodyBuffer)).listenInline(
										(written) -> { parse(buffer); },
										sp
									);
									return null;
								}
							} else if (firstBoundary && b == '-') {
								boundaryRead = new byte[4 + boundary.length + 2];
								boundaryRead[2] = '-';
								boundaryPos = 3;
							}
							continue;
						}
						if (boundaryPos == 1) {
							if (b == '\n') {
								boundaryRead[boundaryPos++] = b;
								continue;
							}
							// not a boundary
							if (!notBoundary(buffer))
								return null;
							continue;
						}
						if (boundaryPos < 4) {
							if (b == '-') {
								boundaryRead[boundaryPos++] = b;
								continue;
							}
							// not a boundary
							if (!notBoundary(buffer))
								return null;
							continue;
						}
						if (boundaryPos < 4 + boundary.length) {
							if (b == boundary[boundaryPos - 4]) {
								boundaryRead[boundaryPos++] = b;
								continue;
							}
							// not a boundary
							if (!notBoundary(buffer))
								return null;
							continue;
						}
						if (boundaryPos == 4 + boundary.length) {
							if (b == '\r' || b == '-') {
								boundaryRead[boundaryPos++] = b;
								continue;
							}
							// not a boundary
							if (!notBoundary(buffer))
								return null;
							continue;
						}
						if (boundaryRead[boundaryPos - 1] == '\r') {
							if (b == '\n') {
								firstBoundary = false;
								// end of current body
								if (body != null) {
									createPart(buffer);
									return null;
								}
								// expecting next header
								header = new MimeUtil.HeadersLinesReceiver(new LinkedList<>());
								headerLine = new StringBuilder(128);
								body = null;
								bodyBufferPos = 0;
								boundaryRead = null;
								boundaryPos = 0;
								continue;
							}
							// not a boundary
							if (!notBoundary(buffer))
								return null;
							continue;
						}
						if (b == '-') {
							// end of multipart
							if (body != null) {
								createPart(null);
								return null;
							}
							sp.unblock();
							return null;
						}
						// not a boundary
						if (!notBoundary(buffer))
							return null;
						continue;
					}
					nextBuffer();
					return null;
				}
			}.start();
		}
		
		private boolean notBoundary(ByteBuffer buffer) {
			firstBoundary = false;
			if (body == null) {
				boundaryPos = 0;
				return true;
			}
			for (int i = 0; i < boundaryPos; ++i) {
				bodyBuffer[bodyBufferPos++] = boundaryRead[i];
				if (bodyBufferPos == bodyBuffer.length) {
					int j = i;
					body.writeAsync(ByteBuffer.wrap(bodyBuffer)).listenInline(
						(written) -> {
							if (j < boundaryPos - 1) {
								System.arraycopy(boundaryRead, j, bodyBuffer, 0, boundaryPos - j);
								bodyBufferPos = boundaryPos - j;
							} else {
								bodyBufferPos = 0;
							}
							boundaryPos = 0;
							// resume parsing
							parse(buffer);
						},
						sp
					);
					return false;
				}
			}
			return true;
		}
		
		private void createPart(ByteBuffer buffer) {
			// first, we flush the body content if any
			if (bodyBufferPos > 0) {
				body.writeAsync(ByteBuffer.wrap(bodyBuffer, 0, bodyBufferPos)).listenInline(
					(written) -> {
						bodyBufferPos = 0;
						createPart(buffer);
					},
					sp
				);
				return;
			}
			// then we create the part
			body.seekSync(SeekType.FROM_BEGINNING, 0);
			MultipartEntity.this.createPart(header.getHeaders(), body).listenInline(
				(part) -> {
					parts.add(part);
					if (buffer != null) {
						// expecting next header
						header = new MimeUtil.HeadersLinesReceiver(new LinkedList<>());
						headerLine = new StringBuilder(128);
						body = null;
						bodyBufferPos = 0;
						boundaryRead = null;
						boundaryPos = 0;
						parse(buffer);
						return;
					}
					sp.unblock();
				},
				sp
			);
		}
		
	}

}
