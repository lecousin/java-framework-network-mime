package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.IAsync;

/** Decoder for a specific content encoding. */
public interface ContentDecoder {

	/** Decode the received data. */
	IAsync<IOException> decode(ByteBuffer data);
	
	/** Finalize the decoding. */
	IAsync<IOException> endOfData();
	
}
