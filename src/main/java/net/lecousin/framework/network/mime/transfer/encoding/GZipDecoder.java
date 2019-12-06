package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.compression.gzip.GZipReadable;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.out2in.OutputToInputBuffers;

/** Decode gzip content. */
public class GZipDecoder implements ContentDecoder {
	
	/** Constructor. */
	public GZipDecoder(ContentDecoder next) {
		this.next = next;
		tmpIO = new OutputToInputBuffers(true, 4, Task.PRIORITY_NORMAL);
		gzip = new GZipReadable(tmpIO, Task.PRIORITY_NORMAL);
		unzip(null);
	}
	
	private ContentDecoder next;
	private OutputToInputBuffers tmpIO;
	private GZipReadable gzip;
	private Async<IOException> done = new Async<>();
	
	@Override
	public IAsync<IOException> decode(ByteBuffer data) {
		if (done.isDone())
			return done;
		return tmpIO.writeAsync(data);
	}
	
	@Override
	public IAsync<IOException> endOfData() {
		tmpIO.endOfData();
		done.onDone(() -> {
			tmpIO.closeAsync();
			gzip.closeAsync();
		});
		return done;
	}
	
	private void unzip(IAsync<IOException> previous) {
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		AsyncSupplier<Integer, IOException> unzip = gzip.readAsync(buffer);
		Task.Cpu<Void, NoException> task =
		new Task.Cpu.FromRunnable("Transfer unzipped data to next content decoder", Task.PRIORITY_NORMAL, () -> {
			int nb = unzip.getResult().intValue();
			if (nb <= 0)
				next.endOfData().onDone(done);
			else {
				buffer.flip();
				unzip(next.decode(buffer));
			}
		});
		if (previous == null)
			unzip.thenStart(task, done);
		else
			previous.onDone(() -> unzip.thenStart(task, done));
	}
	
}
