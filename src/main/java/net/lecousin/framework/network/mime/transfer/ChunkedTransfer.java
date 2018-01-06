package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.AsyncWork.AsyncWorkListener;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.JoinPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.concurrent.util.production.simple.Consumer;
import net.lecousin.framework.concurrent.util.production.simple.Production;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.util.IOReaderAsProducer;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.mime.MIME;
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

	public static final byte[] FINAL_CHUNK = new byte[] { '0', '\r', '\n', '\r', '\n' };
	
	/** Constructor. */
	public ChunkedTransfer(MIME mime, ContentDecoder decoder) {
		super(mime, decoder);
	}
	
	private boolean needSize = true;
	private boolean chunkSizeDone = false;
	private boolean chunkExtension = false;
	private long chunkSize = -1;
	private long chunkUsed = 0;
	private int chunkSizeChars = 0;
	
	@Override
	public AsyncWork<Boolean, IOException> consume(ByteBuffer buf) {
		AsyncWork<Boolean, IOException> result = new AsyncWork<>();
		ChunkConsumer task = new ChunkConsumer(buf, result);
		task.start();
		return result;
	}

	@Override
	public boolean isExpectingData() {
		return true;
	}
	
	private class ChunkConsumer extends Task.Cpu<Void,NoException> {
		private ChunkConsumer(ByteBuffer buf, AsyncWork<Boolean, IOException> ondone) {
			super("Reading chunk of data", Task.PRIORITY_NORMAL);
			this.buf = buf;
			this.ondone = ondone;
		}
		
		private ByteBuffer buf;
		private AsyncWork<Boolean, IOException> ondone;
		
		@Override
		public Void run() {
			while (true) {
				if (!buf.hasRemaining()) {
					if (MIME.logger.isTraceEnabled())
						MIME.logger.trace("End of chunck data consumed, wait for more data");
					ondone.unblockSuccess(Boolean.FALSE);
					return null;
				}
				if (needSize) {
					int i = buf.get() & 0xFF;
					if (MIME.logger.isTraceEnabled())
						MIME.logger.trace("Chunk size character: " + ((char)i)
							+ " (" + i + "), so far size is: " + chunkSize);
					if (chunkSizeChars == 8) {
						// already get the 8 characters
						if (i == '\n') {
							// end of line for chunk size
							needSize = false;
							chunkSizeDone = false;
							chunkExtension = false;
							if (chunkSize == 0) {
								// final chunk of 0
								decoder.endOfData().listenInline(
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
						if (MIME.logger.isTraceEnabled())
							MIME.logger.trace("Start chunk extension");
						chunkSizeDone = true;
						chunkExtension = true;
						continue;
					}
					if (i == '\n') {
						// end of chunk line
						if (MIME.logger.isTraceEnabled())
							MIME.logger.trace("End of chunk line, chunk size is " + chunkSize);
						needSize = false;
						chunkSizeDone = false;
						chunkExtension = false;
						if (chunkSize == 0) {
							// final chunk of 0
							decoder.endOfData().listenInline(
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
						if (MIME.logger.isTraceEnabled())
							MIME.logger.trace("end of chunk size, wait for end of line");
						chunkSizeDone = true;
						continue;
					}
					int isize = StringUtil.decodeHexa((char)i);
					if (isize == -1) {
						if (MIME.logger.isErrorEnabled())
							MIME.logger.error("Invalid chunk size: character '" + (char)i 
								+ "' is not a valid hexadecimal character.");
						ondone.unblockError(new IOException("Invalid chunk size"));
						return null;
					}
					if (chunkSize < 0)
						chunkSize = isize;
					else
						chunkSize = (chunkSize << 4) + isize;
					chunkSizeChars++;
					continue;
				}
				int l = buf.remaining();
				if (l > chunkSize - chunkUsed) {
					int nb = (int)(chunkSize - chunkUsed);
					int limit = buf.limit();
					buf.limit(buf.position() + nb);
					chunkUsed += nb;
					if (chunkUsed == chunkSize) {
						needSize = true;
						chunkSize = -1;
						chunkSizeChars = 0;
						chunkUsed = 0;
					}
					if (MIME.logger.isTraceEnabled())
						MIME.logger.trace("Consume end of chunk: " + nb + " bytes, data still available after");
					ISynchronizationPoint<IOException> decode = decoder.decode(buf);
					decode.listenInline(new Runnable() {
						@Override
						public void run() {
							buf.limit(limit);
							if (decode.isSuccessful())
								new ChunkConsumer(buf, ondone).start();
							else
								ondone.unblockError(IO.error(decode.getError()));
						}
					});
				} else {
					chunkUsed += l;
					if (chunkUsed == chunkSize) {
						if (MIME.logger.isTraceEnabled())
							MIME.logger.trace("Consume end of chunk: " + l + " bytes, no more data available");
						needSize = true;
						chunkSize = -1;
						chunkSizeChars = 0;
						chunkUsed = 0;
					} else {
						if (MIME.logger.isTraceEnabled())
							MIME.logger.trace("Consume part of chunk: " + l + " bytes, " + chunkUsed + "/" + chunkSize + " consumed so far, no more data available");
					}
					ISynchronizationPoint<IOException> decode = decoder.decode(buf);
					decode.listenInline(new Runnable() {
						@Override
						public void run() {
							if (decode.isSuccessful())
								ondone.unblockSuccess(Boolean.FALSE);
							else
								ondone.unblockError(IO.error(decode.getError()));
						}
					});
				}
				break;
			}
			return null;
		}
	}
	
	/** Send data from the given Readable to the client using chunked transfer. */
	public static SynchronizationPoint<IOException> send(TCPRemote client, IO.Readable data, int bufferSize, int maxBuffers) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		Production<ByteBuffer> production = new Production<ByteBuffer>(
			new IOReaderAsProducer(data, bufferSize), maxBuffers,
		new Consumer<ByteBuffer>() {
			@Override
			public AsyncWork<Void,IOException> consume(ByteBuffer product) {
				if (!product.hasRemaining()) {
					return new AsyncWork<Void,IOException>(null, null);
				}
				int size = product.remaining();
				if (MIME.logger.isTraceEnabled())
					MIME.logger.trace("ChunkedTransfer.send from Readable: send chunk of " + size);
				ByteBuffer header = ByteBuffer.wrap((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.US_ASCII));
				ISynchronizationPoint<IOException> sendHeader = client.send(header);
				ISynchronizationPoint<IOException> sendProduct = client.send(product);
				ISynchronizationPoint<IOException> sendEndOfChunk = client.send(ByteBuffer.wrap(MIME.CRLF));
				AsyncWork<Void,IOException> chunk = new AsyncWork<>();
				sendEndOfChunk.listenInline(() -> {
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
			public AsyncWork<Void, IOException> endOfProduction() {
				if (MIME.logger.isTraceEnabled())
					MIME.logger.trace("ChunkedTransfer.send from Readable: send final chunk");
				ISynchronizationPoint<IOException> finalChunk = client.send(ByteBuffer.wrap(FINAL_CHUNK));
				AsyncWork<Void, IOException> end = new AsyncWork<>();
				finalChunk.listenInline(() -> {
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
				if (error instanceof IOException)
					result.error((IOException)error);
				else
					result.error(new IOException(error));
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
				production.getSyncOnFinished().listenInline(new AsyncWorkListener<Void, Exception>() {
					@Override
					public void ready(Void r) {
						result.unblock();
					}
					
					@Override
					public void error(Exception error) {
						if (error instanceof IOException)
							result.error((IOException)error);
						else
							result.error(new IOException(error));
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
	public static SynchronizationPoint<IOException> send(TCPRemote client, IO.Readable.Buffered data) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		byte[] chunkHeader = new byte[10];
		chunkHeader[8] = (byte)'\r';
		chunkHeader[9] = (byte)'\n';
		sendNextBuffer(client, data, result, chunkHeader);
		return result;
	}
	
	private static void sendNextBuffer(
		TCPRemote client, IO.Readable.Buffered data, SynchronizationPoint<IOException> result, byte[] chunkHeader
	) {
		data.readNextBufferAsync().listenInline(
			(buffer) -> {
				if (buffer == null) {
					// send final chunk
					if (MIME.logger.isTraceEnabled())
						MIME.logger.trace("ChunkedTransfer.send from Buffered: Send final chunk to " + client);
					ISynchronizationPoint<IOException> finalChunk = client.send(ByteBuffer.wrap(FINAL_CHUNK));
					finalChunk.listenInline(
						() -> {
							result.unblock();
						},
						(error) -> {
							result.error(error);
						},
						(cancel) -> {
							result.cancel(cancel);
						}
					);
					return;
				}
				new Task.Cpu<Void, NoException>("Send chunk of data to TCP Client", data.getPriority()) {
					@Override
					public Void run() {
						int size = buffer.remaining();
						if (MIME.logger.isTraceEnabled())
							MIME.logger.trace("ChunkedTransfer.send from Buffered: Send chunk of " + size + " bytes to " + client);
						chunkHeader[0] = (byte)StringUtil.encodeHexaDigit((size & 0xF0000000) >> 28);
						chunkHeader[1] = (byte)StringUtil.encodeHexaDigit((size & 0x0F000000) >> 24);
						chunkHeader[2] = (byte)StringUtil.encodeHexaDigit((size & 0x00F00000) >> 20);
						chunkHeader[3] = (byte)StringUtil.encodeHexaDigit((size & 0x000F0000) >> 16);
						chunkHeader[4] = (byte)StringUtil.encodeHexaDigit((size & 0x0000F000) >> 12);
						chunkHeader[5] = (byte)StringUtil.encodeHexaDigit((size & 0x00000F00) >> 8);
						chunkHeader[6] = (byte)StringUtil.encodeHexaDigit((size & 0x000000F0) >> 4);
						chunkHeader[7] = (byte)StringUtil.encodeHexaDigit((size & 0x0000000F));
						ISynchronizationPoint<IOException> sendHeader = client.send(ByteBuffer.wrap(chunkHeader));
						ISynchronizationPoint<IOException> sendProduct = client.send(buffer);
						ISynchronizationPoint<IOException> sendEndOfChunk = client.send(ByteBuffer.wrap(MIME.CRLF));
						JoinPoint.fromSynchronizationPointsSimilarError(sendHeader, sendProduct, sendEndOfChunk)
							.listenInline(
								() -> {
									sendNextBuffer(client, data, result, chunkHeader);
								},
								(error) -> {
									result.error(error);
								},
								(cancel) -> {
									result.cancel(cancel);
								}
							);
						return null;
					}
				}.start();
			},
			(error) -> {
				result.error(error);
			},
			(cancel) -> {
				result.cancel(cancel);
			}
		);
	}
	
}
