package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;

/** Abstract content decoder that needs steps to decode data. */
public abstract class AbstractStepDecoder implements ContentDecoder {

	protected AbstractStepDecoder(ContentDecoder next) {
		this.next = next;
	}
	
	private ContentDecoder next;
	private Async<IOException> lastDecode = null;

	@Override
	public IAsync<IOException> decode(ByteBuffer data) {
		Async<IOException> decode = new Async<>();
		Task<Void, IOException> task = new Task.Cpu<Void, IOException>("Decoding QuotedPrintable data", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				ByteBuffer decoded;
				try { decoded = decodeStep(data); }
				catch (IOException e) {
					decode.error(e);
					return null;
				}
				IAsync<IOException> write = next.decode(decoded);
				write.onDone(() -> {
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
		Async<IOException> previous = lastDecode;
		lastDecode = decode;
		previous.onDone(() -> {
			if (previous.hasError()) decode.error(decode.getError());
			else if (previous.isCancelled()) decode.cancel(decode.getCancelEvent());
			else task.start();
		});
		return decode;
	}
	
	protected abstract ByteBuffer decodeStep(ByteBuffer data) throws IOException;
	
	@Override
	public IAsync<IOException> endOfData() {
		return next.endOfData();
	}

}
