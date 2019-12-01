package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.AsyncSupplier.Listener;
import net.lecousin.framework.concurrent.async.CancelException;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.util.production.simple.Consumer;
import net.lecousin.framework.concurrent.util.production.simple.Production;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.util.IOReaderAsProducer;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;

/**
 * Default transfer, using Content-Length to know how much bytes need to be read, and read it.
 */
public class IdentityTransfer extends TransferReceiver {
	
	public static final String TRANSFER_NAME = "identity";

	/** Constructor. */
	public IdentityTransfer(MimeMessage mime, ContentDecoder decoder) throws IOException {
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
	public AsyncSupplier<Boolean, IOException> consume(ByteBuffer buf) {
		if (eot)
			return new AsyncSupplier<>(Boolean.TRUE, null);
		AsyncSupplier<Boolean, IOException> result = new AsyncSupplier<>();
		int l = buf.remaining();
		if (pos + l > size) {
			int l2 = (int)(size - pos);
			pos += l2;
			eot = pos == size;
			int limit = buf.limit();
			buf.limit(limit - (l - l2));
			IAsync<IOException> decode = decoder.decode(buf);
			decode.onDone(() -> {
				buf.limit(limit);
				if (decode.isSuccessful()) {
					if (!eot)
						result.unblockSuccess(Boolean.FALSE);
					else
						decoder.endOfData().onDone(() -> result.unblockSuccess(Boolean.TRUE), result);
				} else if (decode.hasError()) {
					result.unblockError(IO.error(decode.getError()));
				} else {
					result.cancel(decode.getCancelEvent());
				}
			});
		} else {
			pos += l;
			eot = pos == size;
			IAsync<IOException> decode = decoder.decode(buf);
			decode.onDone(() -> {
				if (decode.isSuccessful()) {
					if (!eot)
						result.unblockSuccess(Boolean.FALSE);
					else
						decoder.endOfData().onDone(() -> result.unblockSuccess(Boolean.TRUE), result);
				} else if (decode.hasError()) {
					result.unblockError(IO.error(decode.getError()));
				} else {
					result.cancel(decode.getCancelEvent());
				}
			});
		}
		return result;
	}
	
	/** Send the data from the given Readable to the client, using default transfer. */
	public static Async<IOException> send(TCPRemote client, IO.Readable data, int bufferSize, int maxBuffers) {
		Async<IOException> result = new Async<>();
		Task<Void,NoException> task = new Task.Cpu<Void,NoException>("Sending MIME body", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				Production<ByteBuffer> production = new Production<>(
					new IOReaderAsProducer(data, bufferSize), maxBuffers,
				new Consumer<ByteBuffer>() {
					@Override
					public AsyncSupplier<Void,IOException> consume(ByteBuffer product) {
						return client.send(product).toAsyncSupplier();
					}
					
					@Override
					public AsyncSupplier<Void, IOException> endOfProduction() {
						return new AsyncSupplier<>(null, null);
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
	
	/** Send the given buffer readable to the network using the TCP client. */
	public static Async<IOException> send(TCPRemote client, IO.Readable.Buffered data) {
		Async<IOException> result = new Async<>();
		sendNextBuffer(client, data, result);
		return result;
	}
	
	private static void sendNextBuffer(TCPRemote client, IO.Readable.Buffered data, Async<IOException> result) {
		data.readNextBufferAsync().onDone(
			buffer -> {
				if (buffer == null) {
					result.unblock();
					return;
				}
				client.send(buffer).onDone(() -> sendNextBuffer(client, data, result), result);
			},
			result
		);
	}

}
