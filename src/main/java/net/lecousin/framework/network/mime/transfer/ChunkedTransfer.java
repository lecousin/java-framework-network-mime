package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.CancelException;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.JoinPoint;
import net.lecousin.framework.concurrent.util.production.simple.Consumer;
import net.lecousin.framework.concurrent.util.production.simple.Production;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.util.IOReaderAsProducer;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.mime.MimeHeader;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;
import net.lecousin.framework.util.StringUtil;
import net.lecousin.framework.util.UnprotectedString;
import net.lecousin.framework.util.UnprotectedStringBuffer;

/**
 * Chunked transfer (Transfer-Encoding: chunked).
 * Each chunk of data starts with the number of bytes expressed in hexadecimal number in ASCII,
 * followed by optional parameters (chunk extension), and a terminating CRLF sequence.
 * A chunk is terminated by CRLF.
 * A terminating chunk is a chunk of 0 byte.
 */
public class ChunkedTransfer extends TransferReceiver {

	public static final String TRANSFER_NAME = "chunked";
	
	private static final byte[] FINAL_CHUNK = new byte[] { '0', '\r', '\n' };
	private static final byte[] CRLF = new byte[] { '\r', '\n' };
	
	/** Constructor. */
	public ChunkedTransfer(MimeMessage mime, ContentDecoder decoder) {
		super(mime, decoder);
	}
	
	private boolean needSize = true;
	private boolean chunkSizeDone = false;
	private boolean chunkExtension = false;
	private long chunkSize = -1;
	private long chunkUsed = 0;
	private int chunkSizeChars = 0;
	private UnprotectedString trailerLine = new UnprotectedString(64);
	
	@Override
	public AsyncSupplier<Boolean, IOException> consume(ByteBuffer buf) {
		AsyncSupplier<Boolean, IOException> result = new AsyncSupplier<>();
		new ChunkConsumer(buf, result).start();
		return result;
	}

	@Override
	public boolean isExpectingData() {
		return true;
	}
	
	private class ChunkConsumer extends Task.Cpu<Void,NoException> {
		private ChunkConsumer(ByteBuffer buf, AsyncSupplier<Boolean, IOException> ondone) {
			super("Reading chunk of data", Task.PRIORITY_NORMAL);
			this.buf = buf;
			this.onDone = ondone;
		}
		
		private ByteBuffer buf;
		private AsyncSupplier<Boolean, IOException> onDone;
		
		@Override
		public Void run() {
			while (true) {
				if (!buf.hasRemaining()) {
					if (mime.getLogger().trace())
						mime.getLogger().trace("End of chunck data consumed, wait for more data");
					onDone.unblockSuccess(Boolean.FALSE);
					return null;
				}
				if (needSize) {
					if (!needSize())
						return null;
					continue;
				}
				if (chunkSize < 0) {
					onDone.unblockError(new IOException("Missing chunk size"));
					return null;
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
			/*
			if (mime.getLogger().trace())
				mime.getLogger().trace("Chunk size character: " + ((char)i)
					+ " (" + i + "), so far size is: " + chunkSize);*/
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
				if (mime.getLogger().trace())
					mime.getLogger().trace("Start chunk extension");
				chunkSizeDone = true;
				chunkExtension = true;
				return true;
			}
			if (i == '\n') {
				// end of chunk line
				if (mime.getLogger().trace())
					mime.getLogger().trace("End of chunk line, chunk size is " + chunkSize);
				needSize = false;
				chunkSizeDone = false;
				chunkExtension = false;
				return true;
			}
			if (chunkExtension) return true;
			if (chunkSizeDone) return true;
			if (i == 0x0D || i == 0x20) {
				// end of chunk size
				if (mime.getLogger().trace())
					mime.getLogger().trace("end of chunk size: " + chunkSize + ", wait for end of line");
				chunkSizeDone = true;
				return true;
			}
			int isize = StringUtil.decodeHexa((char)i);
			if (isize == -1) {
				IOException error = new IOException("Invalid chunk size: character '" + ((char)i)
					+ "' is not a valid hexadecimal character");
				mime.getLogger().error("Invalid chunked data", error);
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
				int limit = buf.limit();
				buf.limit(buf.position() + nb);
				int nextPos = buf.position() + nb;
				chunkUsed += nb;
				if (chunkUsed == chunkSize) {
					needSize = true;
					chunkSize = -1;
					chunkSizeChars = 0;
					chunkUsed = 0;
				}
				if (mime.getLogger().trace())
					mime.getLogger().trace("Consume end of chunk: " + nb + " bytes, data still available after");
				IAsync<IOException> decode = decoder.decode(buf);
				decode.onDone(() -> {
					buf.limit(limit);
					if (decode.isSuccessful()) {
						buf.position(nextPos);
						if (mime.getLogger().trace())
							mime.getLogger().trace(
								"Chunk consumed successfully, start a new consumer for the "
								+ buf.remaining() + " remaining bytes");
						new ChunkConsumer(buf, onDone).start();
					} else if (decode.hasError()) {
						onDone.unblockError(IO.error(decode.getError()));
					} else {
						onDone.unblockCancel(decode.getCancelEvent());
					}
				});
			} else {
				chunkUsed += l;
				if (chunkUsed == chunkSize) {
					if (mime.getLogger().trace())
						mime.getLogger().trace("Consume end of chunk: " + l + " bytes, no more data available");
					needSize = true;
					chunkSize = -1;
					chunkSizeChars = 0;
					chunkUsed = 0;
				} else {
					if (mime.getLogger().trace())
						mime.getLogger().trace("Consume part of chunk: " + l + " bytes, "
							+ chunkUsed + "/" + chunkSize + " consumed so far, no more data available");
				}
				IAsync<IOException> decode = decoder.decode(buf);
				decode.onDone(() -> {
					if (decode.isSuccessful())
						onDone.unblockSuccess(Boolean.FALSE);
					else if (decode.hasError())
						onDone.unblockError(IO.error(decode.getError()));
					else
						onDone.unblockCancel(decode.getCancelEvent());
				});
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
						decoder.endOfData().onDone(() -> onDone.unblockSuccess(Boolean.TRUE), onDone);
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
					mime.addHeaderRaw(name, value);
					if (mime.getLogger().trace())
						mime.getLogger().trace("Trailer header received: " + name + ": " + value);
					trailerLine = new UnprotectedString(64);
				} else {
					trailerLine.append(c);
				}
			} while (buf.hasRemaining());
		}
	}
	
	/** Send data from the given Readable to the client using chunked transfer. */
	public static Async<IOException> send(
		TCPRemote client, IO.Readable data, int bufferSize, int maxBuffers, Supplier<List<MimeHeader>> trailerSupplier
	) {
		Async<IOException> result = new Async<>();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(MimeMessage.class);
		Production<ByteBuffer> production = new Production<>(
			new IOReaderAsProducer(data, bufferSize), maxBuffers,
		new Consumer<ByteBuffer>() {
			@Override
			public AsyncSupplier<Void,IOException> consume(ByteBuffer product) {
				if (!product.hasRemaining()) {
					return new AsyncSupplier<>(null, null);
				}
				int size = product.remaining();
				if (logger.trace())
					logger.trace("ChunkedTransfer.send from Readable: send chunk of " + size);
				ByteBuffer header = ByteBuffer.wrap((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.US_ASCII));
				IAsync<IOException> sendHeader = client.send(header);
				IAsync<IOException> sendProduct = client.send(product);
				IAsync<IOException> sendEndOfChunk = client.send(ByteBuffer.wrap(CRLF));
				AsyncSupplier<Void,IOException> chunk = new AsyncSupplier<>();
				sendEndOfChunk.onDone(() -> {
					if (!sendHeader.forwardIfNotSuccessful(chunk) &&
						!sendProduct.forwardIfNotSuccessful(chunk) &&
						!sendEndOfChunk.forwardIfNotSuccessful(chunk))
						chunk.unblockSuccess(null);
				});
				return chunk;
			}
			
			@Override
			public AsyncSupplier<Void, IOException> endOfProduction() {
				if (logger.trace())
					logger.trace("ChunkedTransfer.send from Readable: send final chunk");
				IAsync<IOException> finalChunk = client.send(ByteBuffer.wrap(FINAL_CHUNK));
				IAsync<IOException> trailer;
				if (trailerSupplier != null) {
					List<MimeHeader> trailers = trailerSupplier.get();
					if (trailers != null) {
						UnprotectedStringBuffer trailerString = new UnprotectedStringBuffer();
						for (MimeHeader header : trailers)
							header.appendTo(trailerString);
						trailer = client.send(ByteBuffer.wrap(trailerString.toUsAsciiBytes()));
					} else {
						trailer = new Async<>(true);
					}
				} else {
					trailer = new Async<>(true);
				}
				IAsync<IOException> sendEndOfTransfer = client.send(ByteBuffer.wrap(CRLF));
				AsyncSupplier<Void, IOException> end = new AsyncSupplier<>();
				sendEndOfTransfer.onDone(() -> {
					if (!finalChunk.forwardIfNotSuccessful(end) &&
						!trailer.forwardIfNotSuccessful(end) &&
						!sendEndOfTransfer.forwardIfNotSuccessful(end))
						end.unblockSuccess(null);
					if (!end.forwardIfNotSuccessful(result))
						result.unblock();
				});
				return end;
			}
			
			@Override
			public void error(Exception error) {
				result.error(IO.error(error));
			}
			
			@Override
			public void cancel(CancelException event) {
				result.cancel(event);
			}
		});
		new Task.Cpu.FromRunnable("Sending chunked body", Task.PRIORITY_NORMAL, () -> {
			production.start();
			production.getSyncOnFinished().onDone(result, IO::error);
		}).start();
		return result;
	}
	
	/** Send the given buffered readable by chunk. It uses the readNextBufferAsync method to get a new
	 * buffer of data, and send a chunk with it to the client.
	 */
	public static Async<IOException> send(TCPRemote client, IO.Readable.Buffered data, Supplier<List<MimeHeader>> trailerSupplier) {
		Async<IOException> result = new Async<>();
		byte[] chunkHeader = new byte[10];
		chunkHeader[8] = (byte)'\r';
		chunkHeader[9] = (byte)'\n';
		sendNextBuffer(client, data, result, chunkHeader, trailerSupplier);
		return result;
	}
	
	private static void sendNextBuffer(
		TCPRemote client, IO.Readable.Buffered data, Async<IOException> result, byte[] chunkHeader, Supplier<List<MimeHeader>> trailerSupplier
	) {
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(MimeMessage.class);
		data.readNextBufferAsync().onDone(
			buffer -> {
				if (buffer == null) {
					// send final chunk
					if (logger.trace())
						logger.trace("ChunkedTransfer.send from Buffered: Send final chunk to " + client);
					IAsync<IOException> finalChunk = client.send(ByteBuffer.wrap(FINAL_CHUNK));
					IAsync<IOException> trailer;
					if (trailerSupplier != null) {
						List<MimeHeader> trailers = trailerSupplier.get();
						if (trailers != null) {
							UnprotectedStringBuffer trailerString = new UnprotectedStringBuffer();
							for (MimeHeader header : trailers)
								header.appendTo(trailerString);
							trailer = client.send(ByteBuffer.wrap(trailerString.toUsAsciiBytes()));
						} else {
							trailer = new Async<>(true);
						}
					} else {
						trailer = new Async<>(true);
					}
					IAsync<IOException> sendEndOfTransfer = client.send(ByteBuffer.wrap(CRLF));
					sendEndOfTransfer.onDone(() -> {
						if (!finalChunk.forwardIfNotSuccessful(result) &&
							!trailer.forwardIfNotSuccessful(result) &&
							!sendEndOfTransfer.forwardIfNotSuccessful(result))
							result.unblock();
					});
					return;
				}
				new Task.Cpu<Void, NoException>("Send chunk of data to TCP Client", data.getPriority()) {
					@Override
					public Void run() {
						int size = buffer.remaining();
						if (logger.trace())
							logger.trace("ChunkedTransfer.send from Buffered: Send chunk of "
								+ size + " bytes to " + client);
						chunkHeader[0] = (byte)StringUtil.encodeHexaDigit((size & 0xF0000000) >> 28);
						chunkHeader[1] = (byte)StringUtil.encodeHexaDigit((size & 0x0F000000) >> 24);
						chunkHeader[2] = (byte)StringUtil.encodeHexaDigit((size & 0x00F00000) >> 20);
						chunkHeader[3] = (byte)StringUtil.encodeHexaDigit((size & 0x000F0000) >> 16);
						chunkHeader[4] = (byte)StringUtil.encodeHexaDigit((size & 0x0000F000) >> 12);
						chunkHeader[5] = (byte)StringUtil.encodeHexaDigit((size & 0x00000F00) >> 8);
						chunkHeader[6] = (byte)StringUtil.encodeHexaDigit((size & 0x000000F0) >> 4);
						chunkHeader[7] = (byte)StringUtil.encodeHexaDigit((size & 0x0000000F));
						IAsync<IOException> sendHeader = client.send(ByteBuffer.wrap(chunkHeader));
						IAsync<IOException> sendProduct = client.send(buffer);
						IAsync<IOException> sendEndOfChunk = client.send(ByteBuffer.wrap(CRLF));
						JoinPoint.fromSimilarError(sendHeader, sendProduct, sendEndOfChunk)
							.onDone(() -> sendNextBuffer(client, data, result, chunkHeader, trailerSupplier), result);
						return null;
					}
				}.start();
			},
			result
		);
	}
	
}
