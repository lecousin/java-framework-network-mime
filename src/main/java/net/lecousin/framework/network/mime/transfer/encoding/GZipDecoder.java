package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.compression.gzip.GZipReadable;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
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
	private SynchronizationPoint<IOException> done = new SynchronizationPoint<>();
	
	@Override
	public ISynchronizationPoint<IOException> decode(ByteBuffer data) {
		if (done.isUnblocked())
			return done;
		return tmpIO.writeAsync(data);
	}
	
	@Override
	public ISynchronizationPoint<IOException> endOfData() {
		tmpIO.endOfData();
		done.listenInline(() -> {
			tmpIO.closeAsync();
			gzip.closeAsync();
		});
		return done;
	}
	
	private void unzip(ISynchronizationPoint<IOException> previous) {
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		AsyncWork<Integer, IOException> unzip = gzip.readAsync(buffer);
		Task.Cpu<Void, NoException> task =
		new Task.Cpu<Void, NoException>("Transfer unzipped data to next content decoder", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				if (unzip.hasError()) done.error(unzip.getError());
				else if (unzip.isCancelled()) done.cancel(unzip.getCancelEvent());
				else {
					int nb = unzip.getResult().intValue();
					if (nb <= 0)
						next.endOfData().listenInline(done);
					else {
						buffer.flip();
						unzip(next.decode(buffer));
					}
				}
				return null;
			}
		};
		if (previous == null)
			unzip.listenAsync(task, true);
		else
			previous.listenInline(() -> { unzip.listenAsync(task, true); });
	}
	
}
