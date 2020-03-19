package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.encoding.EncodingException;
import net.lecousin.framework.encoding.HexaDecimalEncoding;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.text.ByteArrayStringIso8859;
import net.lecousin.framework.text.CharArrayString;
import net.lecousin.framework.util.DebugUtil;

/**
 * Chunked transfer (Transfer-Encoding: chunked).
 * Each chunk of data starts with the number of bytes expressed in hexadecimal number in ASCII,
 * followed by optional parameters (chunk extension), and a terminating CRLF sequence.
 * A chunk is terminated by CRLF.
 * A terminating chunk is a chunk of 0 byte.
 */
public final class ChunkedTransfer {
	
	private ChunkedTransfer() { /* no instance. */ }

	public static final String TRANSFER_NAME = "chunked";
	
	private static final byte[] FINAL_CHUNK = new byte[] { '\r', '\n', '0', '\r', '\n', '\r', '\n' };
	private static final byte[] CRLF = new byte[] { '\r', '\n' };
	
	/** Consumer to receive a chunked transfer. */
	public static class Receiver implements PartialAsyncConsumer<ByteBuffer, IOException> {
	
		/** Constructor. */
		public Receiver(MimeHeaders headers, AsyncConsumer<ByteBuffer, IOException> consumer) {
			this.headers = headers;
			this.consumer = consumer;
			logger = LCCore.getApplication().getLoggerFactory().getLogger(ChunkedTransfer.class);
		}
		
		private Logger logger;
		private boolean needSize = true;
		private boolean chunkSizeDone = false;
		private boolean chunkExtension = false;
		private long chunkSize = -1;
		private long chunkUsed = 0;
		private int chunkSizeChars = 0;
		private CharArrayString trailerLine = new CharArrayString(64);
		private MimeHeaders headers;
		private AsyncConsumer<ByteBuffer, IOException> consumer;
		
		@Override
		public AsyncSupplier<Boolean, IOException> consume(ByteBuffer buf) {
			AsyncSupplier<Boolean, IOException> result = new AsyncSupplier<>();
			consumeChunkTask(buf, result).start();
			return result;
		}
	
		@Override
		public boolean isExpectingData() {
			return true;
		}
		
		private Task<Void, NoException> consumeChunkTask(ByteBuffer buf, AsyncSupplier<Boolean, IOException> ondone) {
			return Task.cpu("Read chunk of data", Task.getCurrentPriority(), new ChunkConsumer(buf, ondone));
		}
		
		private class ChunkConsumer implements Executable<Void,NoException> {
			private ChunkConsumer(ByteBuffer buf, AsyncSupplier<Boolean, IOException> ondone) {
				this.buf = buf;
				this.onDone = ondone;
			}
			
			private ByteBuffer buf;
			private AsyncSupplier<Boolean, IOException> onDone;
			
			@Override
			public Void execute(Task<Void, NoException> t) {
				while (true) {
					if (!buf.hasRemaining()) {
						if (logger.trace())
							logger.trace("End of chunck data consumed, wait for more data");
						onDone.unblockSuccess(Boolean.FALSE);
						return null;
					}
					if (needSize) {
						if (!needSize())
							return null;
						continue;
					}
					if (chunkSize == 0) {
						// final chunk of 0
						consumeTrailer();
						return null;
					}
					consumeChunk();
					break;
				}
				return null;
			}
			
			private boolean needSize() {
				int i = buf.get() & 0xFF;
				if (chunkSizeChars == 8 && i == '\n') {
					// already get the 8 characters
					// end of line for chunk size
					needSize = false;
					chunkSizeDone = false;
					chunkExtension = false;
					return true;
				}
				if (chunkSize < 0 && (i == '\r' || i == '\n'))
					return true;
				if (i == ';') {
					if (chunkSize < 0) {
						IOException error = new IOException("No chunk size before extension");
						logger.error("Invalid chunked data", error);
						onDone.unblockError(error);
						return false;
					}
					if (logger.trace())
						logger.trace("Start chunk extension");
					chunkSizeDone = true;
					chunkExtension = true;
					return true;
				}
				if (i == '\n') {
					// end of chunk line
					if (logger.trace())
						logger.trace("End of chunk line, chunk size is " + chunkSize);
					needSize = false;
					chunkSizeDone = false;
					chunkExtension = false;
					return true;
				}
				if (chunkExtension) return true;
				if (chunkSizeDone) return true;
				if (i == 0x0D || i == 0x20) {
					// end of chunk size
					if (logger.trace())
						logger.trace("end of chunk size: " + chunkSize + ", wait for end of line");
					chunkSizeDone = true;
					return true;
				}
				int isize;
				try { isize = HexaDecimalEncoding.decodeChar((char)i); }
				catch (EncodingException e) {
					StringBuilder msg = new StringBuilder();
					msg.append("Invalid chunk size: character '")
					.append((char)i)
					.append("' is not a valid hexadecimal character. It was found at position 0x")
					.append(Integer.toHexString(buf.position() - 1));
					if (buf.hasArray()) {
						msg.append(" in the following buffer:\r\n");
						DebugUtil.dumpHex(msg, buf.array(), buf.arrayOffset(), buf.limit());
					}
					IOException error = new IOException(msg.toString());
					logger.error("Invalid chunked data", error);
					onDone.unblockError(error);
					return false;
				}
				if (chunkSize < 0)
					chunkSize = isize;
				else
					chunkSize = (chunkSize << 4) + isize;
				chunkSizeChars++;
				return true;
			}
			
			private void consumeChunk() {
				int l = buf.remaining();
				if (l > chunkSize - chunkUsed) {
					int nb = (int)(chunkSize - chunkUsed);
					// sub-buffer of nb bytes
					ByteBuffer subBuffer = buf.duplicate();
					subBuffer.limit(buf.position() + nb);
					// move forward
					buf.position(buf.position() + nb);
					chunkUsed += nb;
					if (chunkUsed == chunkSize) {
						needSize = true;
						chunkSize = -1;
						chunkSizeChars = 0;
						chunkUsed = 0;
					}
					if (logger.trace())
						logger.trace("Consume end of chunk: " + nb + " bytes, data still available after");
					IAsync<IOException> decode = consumer.consume(subBuffer.asReadOnlyBuffer());
					decode.onDone(() -> {
						if (decode.isSuccessful()) {
							if (logger.trace())
								logger.trace(
									"Chunk consumed successfully, start a new consumer for the "
									+ buf.remaining() + " remaining bytes");
							consumeChunkTask(buf, onDone).start();
						} else if (decode.hasError()) {
							onDone.unblockError(IO.error(decode.getError()));
						} else {
							onDone.unblockCancel(decode.getCancelEvent());
						}
					});
				} else {
					chunkUsed += l;
					if (chunkUsed == chunkSize) {
						if (logger.trace())
							logger.trace("Consume end of chunk: " + l + " bytes, no more data available");
						needSize = true;
						chunkSize = -1;
						chunkSizeChars = 0;
						chunkUsed = 0;
					} else {
						if (logger.trace())
							logger.trace("Consume part of chunk: " + l + " bytes, "
								+ chunkUsed + "/" + chunkSize + " consumed so far, no more data available");
					}
					IAsync<IOException> decode = consumer.consume(buf.duplicate().asReadOnlyBuffer());
					buf.position(buf.limit());
					decode.onDone(() -> onDone.unblockSuccess(Boolean.FALSE), onDone, IO::error);
				}
			}
			
			private void consumeTrailer() {
				do {
					char c = (char)(buf.get() & 0xFF);
					if (c == '\n') {
						// end of line
						trailerLine.trim();
						if (trailerLine.length() == 0) {
							// empty line = end of transfer
							if (logger.trace())
								logger.trace("End of trailers");
							consumer.end().onDone(() -> onDone.unblockSuccess(Boolean.TRUE), onDone);
							return;
						}
						// this is a trailer
						int i = trailerLine.indexOf(':');
						String name;
						String value;
						if (i < 0) {
							name = trailerLine.toString();
							value = "";
						} else {
							name = trailerLine.substring(0, i).trim().toString();
							value = trailerLine.substring(i + 1).trim().toString();
						}
						headers.addRawValue(name, value);
						if (logger.trace())
							logger.trace("Trailer header received: " + name + ": " + value);
						trailerLine = new CharArrayString(64);
					} else {
						trailerLine.append(c);
					}
				} while (buf.hasRemaining());
				onDone.unblockSuccess(Boolean.FALSE);
			}
		}
	}
	
	/** Sender for chunked transfer. */
	public static class Sender implements AsyncConsumer<ByteBuffer, IOException> {
		
		/** Constructor. */
		public Sender(AsyncConsumer<ByteBuffer, IOException> sender, Supplier<List<MimeHeader>> trailerSupplier) {
			this.sender = sender;
			this.trailerSupplier = trailerSupplier;
			logger = LCCore.getApplication().getLoggerFactory().getLogger(ChunkedTransfer.class);
		}
		
		private AsyncConsumer<ByteBuffer, IOException> sender;
		private Supplier<List<MimeHeader>> trailerSupplier;
		private boolean needEndOfPreviousChunk = false;
		private Logger logger;
		
		@Override
		public IAsync<IOException> consume(ByteBuffer data) {
			int val = data.remaining();
			byte[] chunkHeader = new byte[12];
			chunkHeader[10] = '\r';
			chunkHeader[11] = '\n';
			int i = 9;
			if (val == 0) {
				chunkHeader[i--] = '0';
			} else while (i >= 0 && val > 0) {
				chunkHeader[i--] = (byte)HexaDecimalEncoding.encodeDigit(val & 0xF);
				val >>= 4;
			}
			if (needEndOfPreviousChunk) {
				chunkHeader[i--] = '\n';
				chunkHeader[i--] = '\r';
			}
			if (logger.trace())
				logger.trace("Sending chunk size: " + new String(chunkHeader, i + 1, 12 - (i + 1), StandardCharsets.US_ASCII));
			IAsync<IOException> sendHeader = sender.consume(ByteBuffer.wrap(chunkHeader, i + 1, 12 - (i + 1)));
			needEndOfPreviousChunk = true;
			Async<IOException> result = new Async<>();
			sendHeader.thenDoOrStart("Send chunk of data", Task.getCurrentPriority(), () -> {
				if (logger.trace())
					logger.trace("Sending chunk data: " + data.remaining());
				IAsync<IOException> sendData = sender.consume(data);
				sendData.onDone(result);
			}, result);
			return result;
		}
		
		@Override
		public IAsync<IOException> end() {
			Async<IOException> result = new Async<>();

			if (logger.trace())
				logger.trace("Sending final chunk of 0");
			List<MimeHeader> trailers = trailerSupplier == null ? null : trailerSupplier.get();
			int start = needEndOfPreviousChunk ? 0 : 2;
			if (trailers == null || trailers.isEmpty()) {
				sender.consume(ByteBuffer.wrap(FINAL_CHUNK, start, FINAL_CHUNK.length - start).asReadOnlyBuffer())
				.thenDoOrStart("Send final chunk", Task.getCurrentPriority(), () -> sender.end().onDone(result), result);
				return result;
			}
			sender.consume(ByteBuffer.wrap(FINAL_CHUNK, start, FINAL_CHUNK.length - start - 2).asReadOnlyBuffer())
				.thenDoOrStart("Send end of chunk transfer", Task.getCurrentPriority(), () -> {
					ByteArrayStringIso8859 s = new ByteArrayStringIso8859(512);
					for (MimeHeader h : trailers)
						h.appendTo(s);
					if (logger.trace())
						logger.trace("Sending trailer after last chunk (" + s.length() + ")");
					sender.consume(s.asByteBuffer()).onDone(() -> sendFinalCRLF(result), result);
				}, result);
			return result;
		}
		
		private void sendFinalCRLF(Async<IOException> result) {
			if (logger.trace())
				logger.trace("Sending final CRLF after last chunk and trailer");
			sender.consume(ByteBuffer.wrap(CRLF).asReadOnlyBuffer())
				.thenDoOrStart("Send final chunk", Task.getCurrentPriority(), () -> sender.end().onDone(result), result);
		}
		
		@Override
		public void error(IOException error) {
			sender.error(error);
		}
		
	}
	
}
