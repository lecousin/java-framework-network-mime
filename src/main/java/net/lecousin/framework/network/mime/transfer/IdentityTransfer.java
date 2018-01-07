package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.AsyncWork.AsyncWorkListener;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.concurrent.util.production.simple.Consumer;
import net.lecousin.framework.concurrent.util.production.simple.Production;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.util.IOReaderAsProducer;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.mime.MIME;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;

/**
 * Default transfer, using Content-Length to know how much bytes need to be read, and read it.
 */
public class IdentityTransfer extends TransferReceiver {

	/** Constructor. */
	public IdentityTransfer(MIME mime, ContentDecoder decoder) throws IOException {
		super(mime, decoder);
		Long s = mime.getContentLength();
		if (s == null)
			throw new IOException("No content length for identity transfer: impossible to transfer data");
		size = s.longValue();
		eot = (size == 0);
	}
	
	private long pos = 0;
	private long size;
	private boolean eot;

	@Override
	public boolean isExpectingData() {
		return size > 0;
	}

	@Override
	public AsyncWork<Boolean, IOException> consume(ByteBuffer buf) {
		if (eot)
			return new AsyncWork<Boolean, IOException>(Boolean.TRUE, null);
		AsyncWork<Boolean, IOException> result = new AsyncWork<>();
		int l = buf.remaining();
		if (pos + l > size) {
			int l2 = (int)(size - pos);
			pos += l2;
			eot = pos == size;
			int limit = buf.limit();
			buf.limit(limit - (l - l2));
			ISynchronizationPoint<IOException> decode = decoder.decode(buf);
			decode.listenInline(new Runnable() {
				@Override
				public void run() {
					buf.limit(limit);
					if (decode.isSuccessful()) {
						if (!eot)
							result.unblockSuccess(Boolean.FALSE);
						else
							decoder.endOfData().listenInline(
								() -> { result.unblockSuccess(Boolean.TRUE); },
								result
							);
					} else
						result.unblockError(IO.error(decode.getError()));
				}
			});
		} else {
			pos += l;
			eot = pos == size;
			ISynchronizationPoint<IOException> decode = decoder.decode(buf);
			decode.listenInline(new Runnable() {
				@Override
				public void run() {
					if (decode.isSuccessful())
						if (!eot)
							result.unblockSuccess(Boolean.FALSE);
						else
							decoder.endOfData().listenInline(
								() -> { result.unblockSuccess(Boolean.TRUE); },
								result
							);
					else
						result.unblockError(IO.error(decode.getError()));
				}
			});
		}
		return result;
	}
	
	/** Send the data from the given Readable to the client, using default transfer. */
	public static SynchronizationPoint<IOException> send(TCPRemote client, IO.Readable data, int bufferSize, int maxBuffers) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		Task<Void,NoException> task = new Task.Cpu<Void,NoException>("Sending MIME body", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				Production<ByteBuffer> production = new Production<ByteBuffer>(
					new IOReaderAsProducer(data, bufferSize), maxBuffers,
				new Consumer<ByteBuffer>() {
					@Override
					public AsyncWork<Void,IOException> consume(ByteBuffer product) {
						return client.send(product).toAsyncWorkVoid();
					}
					
					@Override
					public AsyncWork<Void, IOException> endOfProduction() {
						return new AsyncWork<Void,IOException>(null, null);
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
				production.start();
				production.getSyncOnFinished().listenInline(new AsyncWorkListener<Void, Exception>() {
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
	
	/** Send the given buffer readable to the network using the TCP client. */
	public static SynchronizationPoint<IOException> send(TCPRemote client, IO.Readable.Buffered data) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		sendNextBuffer(client, data, result);
		return result;
	}
	
	private static void sendNextBuffer(TCPRemote client, IO.Readable.Buffered data, SynchronizationPoint<IOException> result) {
		data.readNextBufferAsync().listenInline(
			(buffer) -> {
				if (buffer == null) {
					result.unblock();
					return;
				}
				client.send(buffer).listenInline(
					() -> {
						sendNextBuffer(client, data, result);
					},
					result
				);
			},
			result
		);
	}

}
