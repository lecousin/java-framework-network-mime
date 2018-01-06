package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.encoding.QuotedPrintable;

/**
 * Quoted printable transfer (Transfer-Encoding: quoted-printable).
 * A Content-Length must be specified.
 */
public class QuotedPrintableDecoder implements ContentDecoder {

	/** Constructor. */
	public QuotedPrintableDecoder(ContentDecoder next) {
		this.next = next;
	}
	
	private ContentDecoder next;
	private byte[] previousRemainingData = null;
	private SynchronizationPoint<IOException> lastDecode = null;

	@Override
	public ISynchronizationPoint<IOException> decode(ByteBuffer data) {
		SynchronizationPoint<IOException> decode = new SynchronizationPoint<>();
		Task<Void, IOException> task = new Task.Cpu<Void, IOException>("Decoding QuotedPrintable data", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				ByteBuffer input;
				if (previousRemainingData != null) {
					byte[] b = new byte[previousRemainingData.length + data.remaining()];
					System.arraycopy(previousRemainingData, 0, b, 0, previousRemainingData.length);
					data.get(b, previousRemainingData.length, b.length - previousRemainingData.length);
					input = ByteBuffer.wrap(b);
				} else
					input = data;
				ByteBuffer decoded;
				try { decoded = QuotedPrintable.decode(input); }
				catch (IOException e) {
					decode.error(e);
					return null;
				}
				if (input.hasRemaining()) {
					previousRemainingData = new byte[input.remaining()];
					input.get(previousRemainingData, 0, input.remaining());
				} else
					previousRemainingData = null;
				ISynchronizationPoint<IOException> write = next.decode(decoded);
				write.listenInline(() -> {
					if (write.hasError()) decode.error(write.getError());
					else if (write.isCancelled()) decode.cancel(write.getCancelEvent());
					else decode.unblock();
				});
				return null;
			}
		};
		if (lastDecode == null) {
			lastDecode = decode;
			task.start();
			return decode;
		}
		SynchronizationPoint<IOException> previous = lastDecode;
		lastDecode = decode;
		previous.listenInline(() -> {
			if (previous.hasError()) decode.error(decode.getError());
			else if (previous.isCancelled()) decode.cancel(decode.getCancelEvent());
			else task.start();
		});
		return decode;
	}
	
	@Override
	public ISynchronizationPoint<IOException> endOfData() {
		return next.endOfData();
	}

}
