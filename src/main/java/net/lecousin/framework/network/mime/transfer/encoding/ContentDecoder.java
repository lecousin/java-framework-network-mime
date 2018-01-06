package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;

/** Decoder for a specific content encoding. */
public interface ContentDecoder {

	/** Decode the received data. */
	ISynchronizationPoint<IOException> decode(ByteBuffer data);
	
	/** Finalize the decoding. */
	ISynchronizationPoint<IOException> endOfData();
	
}
