package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.AsyncSupplier.Listener;
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
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;
import net.lecousin.framework.util.StringUtil;

/**
 * Chunked transfer (Transfer-Encoding: chunked).
 * Each chunk of data starts with the number of bytes expressed in hexadecimal number in ASCII,
 * followed by optional parameters (chunk extension), and a terminating CRLF sequence.
 * A chunk is terminated by CRLF.
 * A terminating chunk is a chunk of 0 byte.
 */
public class ChunkedTransfer extends TransferReceiver {

	// TODO support for trailer headers (https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Trailer)

	private static final byte[] FINAL_CHUNK = new byte[] { '0', '\r', '\n', '\r', '\n' };
	
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
	
	@Override
	public AsyncSupplier<Boolean, IOException> consume(ByteBuffer buf) {
		AsyncSupplier<Boolean, IOException> result = new AsyncSupplier<>();
		ChunkConsumer task = new ChunkConsumer(buf, result);
		task.start();
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
			this.ondone = ondone;
		}
		
		private ByteBuffer buf;
		private AsyncSupplier<Boolean, IOException> ondone;
		
		@Override
		public Void run() {
			while (true) {
				if (!buf.hasRemaining()) {
					if (mime.getLogger().trace())
						mime.getLogger().trace("End of chunck data consumed, wait for more data");
					ondone.unblockSuccess(Boolean.FALSE);
					return null;
				}
				if (needSize) {
					int i = buf.get() & 0xFF;
					/*
					if (mime.getLogger().trace())
						mime.getLogger().trace("Chunk size character: " + ((char)i)
							+ " (" + i + "), so far size is: " + chunkSize);*/
					if (chunkSizeChars == 8) {
						// already get the 8 characters
						if (i == '\n') {
							// end of line for chunk size
							needSize = false;
							chunkSizeDone = false;
							chunkExtension = false;
							if (chunkSize == 0) {
								// final chunk of 0
								decoder.endOfData().onDone(
									() -> { ondone.unblockSuccess(Boolean.TRUE); },
									ondone
								);
								return null;
							}
							continue;
						}
					}
					if (chunkSize < 0 && (i == '\r' || i == '\n')) continue;
					if (i == ';') {
						if (mime.getLogger().trace())
							mime.getLogger().trace("Start chunk extension");
						chunkSizeDone = true;
						chunkExtension = true;
						continue;
					}
					if (i == '\n') {
						// end of chunk line
						if (mime.getLogger().trace())
							mime.getLogger().trace("End of chunk line, chunk size is " + chunkSize);
						needSize = false;
						chunkSizeDone = false;
						chunkExtension = false;
						if (chunkSize == 0) {
							// final chunk of 0
							decoder.endOfData().onDone(
									() -> { ondone.unblockSuccess(Boolean.TRUE); },
									ondone
								);
							return null;
						}
						continue;
					}
					if (chunkExtension) continue;
					if (chunkSizeDone) continue;
					if (i == 0x0D || i == 0x20) {
						// end of chunk size
						if (mime.getLogger().trace())
							mime.getLogger().trace("end of chunk size: " + chunkSize + ", wait for end of line");
						chunkSizeDone = true;
						continue;
					}
					int isize = StringUtil.decodeHexa((char)i);
					if (isize == -1) {
						IOException error = new IOException("Invalid chunk size: character '" + ((char)i)
							+ "' is not a valid hexadecimal character");
						mime.getLogger().error("Invalid chunked data", error);
						ondone.unblockError(error);
						return null;
					}
					if (chunkSize < 0)
						chunkSize = isize;
					else
						chunkSize = (chunkSize << 4) + isize;
					chunkSizeChars++;
					continue;
				}
				if (chunkSize < 0) {
					ondone.unblockError(new IOException("Missing chunk size"));
					return null;
				}
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
					decode.onDone(new Runnable() {
						@Override
						public void run() {
							buf.limit(limit);
							if (decode.isSuccessful()) {
								buf.position(nextPos);
								if (mime.getLogger().trace())
									mime.getLogger().trace(
										"Chunk consumed successfully, start a new consumer for the "
										+ buf.remaining() + " remaining bytes");
								new ChunkConsumer(buf, ondone).start();
							} else if (decode.hasError())
								ondone.unblockError(IO.error(decode.getError()));
							else
								ondone.unblockCancel(decode.getCancelEvent());
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
					decode.onDone(new Runnable() {
						@Override
						public void run() {
							if (decode.isSuccessful())
								ondone.unblockSuccess(Boolean.FALSE);
							else if (decode.hasError())
								ondone.unblockError(IO.error(decode.getError()));
							else
								ondone.unblockCancel(decode.getCancelEvent());
						}
					});
				}
				break;
			}
			return null;
		}
	}
	
	/** Send data from the given Readable to the client using chunked transfer. */
	public static Async<IOException> send(TCPRemote client, IO.Readable data, int bufferSize, int maxBuffers) {
		Async<IOException> result = new Async<>();
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(MimeMessage.class);
		Production<ByteBuffer> production = new Production<ByteBuffer>(
			new IOReaderAsProducer(data, bufferSize), maxBuffers,
		new Consumer<ByteBuffer>() {
			@Override
			public AsyncSupplier<Void,IOException> consume(ByteBuffer product) {
				if (!product.hasRemaining()) {
					return new AsyncSupplier<Void,IOException>(null, null);
				}
				int size = product.remaining();
				if (logger.trace())
					logger.trace("ChunkedTransfer.send from Readable: send chunk of " + size);
				ByteBuffer header = ByteBuffer.wrap((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.US_ASCII));
				IAsync<IOException> sendHeader = client.send(header);
				IAsync<IOException> sendProduct = client.send(product);
				IAsync<IOException> sendEndOfChunk = client.send(ByteBuffer.wrap(FINAL_CHUNK, 1, 2));
				AsyncSupplier<Void,IOException> chunk = new AsyncSupplier<>();
				sendEndOfChunk.onDone(() -> {
					if (sendHeader.hasError()) chunk.error(sendHeader.getError());
					else if (sendHeader.isCancelled()) chunk.cancel(sendHeader.getCancelEvent());
					else if (sendProduct.hasError()) chunk.error(sendProduct.getError());
					else if (sendProduct.isCancelled()) chunk.cancel(sendProduct.getCancelEvent());
					else if (sendEndOfChunk.hasError()) chunk.error(sendEndOfChunk.getError());
					else if (sendEndOfChunk.isCancelled()) chunk.cancel(sendEndOfChunk.getCancelEvent());
					else chunk.unblockSuccess(null);
				});
				return chunk;
			}
			
			@Override
			public AsyncSupplier<Void, IOException> endOfProduction() {
				if (logger.trace())
					logger.trace("ChunkedTransfer.send from Readable: send final chunk");
				IAsync<IOException> finalChunk = client.send(ByteBuffer.wrap(FINAL_CHUNK));
				AsyncSupplier<Void, IOException> end = new AsyncSupplier<>();
				finalChunk.onDone(() -> {
					if (finalChunk.hasError()) {
						end.error(finalChunk.getError());
						result.error(finalChunk.getError());
					} else if (finalChunk.isCancelled()) {
						end.cancel(finalChunk.getCancelEvent());
						result.cancel(finalChunk.getCancelEvent());
					} else {
						end.unblockSuccess(null);
						result.unblock();
					}
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
		Task<Void,NoException> task = new Task.Cpu<Void, NoException>("Sending chunked body", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				production.start();
				production.getSyncOnFinished().listen(new Listener<Void, Exception>() {
					@Override
					public void ready(Void r) {
						result.unblock();
					}
					
					@Override
					public void error(Exception error) {
						result.error(IO.error(error));
					}
					
					@Override
					public void cancelled(CancelException event) {
						result.cancel(event);
					}
				});
				return null;
			}
		};
		task.start();
		return result;
	}
	
	/** Send the given buffered readable by chunk. It uses the readNextBufferAsync method to get a new
	 * buffer of data, and send a chunk with it to the client.
	 */
	public static Async<IOException> send(TCPRemote client, IO.Readable.Buffered data) {
		Async<IOException> result = new Async<>();
		byte[] chunkHeader = new byte[10];
		chunkHeader[8] = (byte)'\r';
		chunkHeader[9] = (byte)'\n';
		sendNextBuffer(client, data, result, chunkHeader);
		return result;
	}
	
	private static void sendNextBuffer(
		TCPRemote client, IO.Readable.Buffered data, Async<IOException> result, byte[] chunkHeader
	) {
		Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(MimeMessage.class);
		data.readNextBufferAsync().onDone(
			(buffer) -> {
				if (buffer == null) {
					// send final chunk
					if (logger.trace())
						logger.trace("ChunkedTransfer.send from Buffered: Send final chunk to " + client);
					IAsync<IOException> finalChunk = client.send(ByteBuffer.wrap(FINAL_CHUNK));
					finalChunk.onDone(result);
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
						IAsync<IOException> sendEndOfChunk = client.send(ByteBuffer.wrap(FINAL_CHUNK, 1, 2));
						JoinPoint.fromSimilarError(sendHeader, sendProduct, sendEndOfChunk)
							.onDone(() -> {
									sendNextBuffer(client, data, result, chunkHeader);
							}, result);
						return null;
					}
				}.start();
			},
			result
		);
	}
	
}
