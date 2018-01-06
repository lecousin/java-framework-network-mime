package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.IO;

/** Last decoder in the chain, writing the decoded data into a Writable. */
public class IdentityDecoder implements ContentDecoder {

	/** Constructor. */
	public IdentityDecoder(IO.Writable out) {
		this.out = out;
	}
	
	private IO.Writable out;
	
	@Override
	public ISynchronizationPoint<IOException> decode(ByteBuffer data) {
		if (!data.hasRemaining())
			return new SynchronizationPoint<>(true);
		return out.writeAsync(data);
	}
	
	@Override
	public ISynchronizationPoint<IOException> endOfData() {
		return new SynchronizationPoint<>(true);
	}
	
}
