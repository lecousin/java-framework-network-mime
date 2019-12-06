package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.IO;

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
				catch (Exception e) {
					decode.error(IO.error(e));
					return null;
				}
				IAsync<IOException> write = next.decode(decoded);
				write.onDone(decode);
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
		previous.thenStart(task, decode);
		return decode;
	}
	
	protected abstract ByteBuffer decodeStep(ByteBuffer data) throws IOException;
	
	@Override
	public IAsync<IOException> endOfData() {
		return next.endOfData();
	}

}
