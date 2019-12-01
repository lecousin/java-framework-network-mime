package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.IO;

/** Last decoder in the chain, writing the decoded data into a Writable. */
public class IdentityDecoder implements ContentDecoder {

	/** Constructor. */
	public IdentityDecoder(IO.Writable out) {
		this.out = out;
	}
	
	private IO.Writable out;
	
	@Override
	public IAsync<IOException> decode(ByteBuffer data) {
		if (!data.hasRemaining())
			return new Async<>(true);
		return out.writeAsync(data);
	}
	
	@Override
	public IAsync<IOException> endOfData() {
		return new Async<>(true);
	}
	
}
