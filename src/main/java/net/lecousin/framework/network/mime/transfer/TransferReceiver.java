package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.network.mime.MIME;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;

/**
 * MIME Transfer, based on the Transfer-Encoding or Content-Transfer-Encoding header.
 */
@SuppressFBWarnings("URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
public abstract class TransferReceiver {

	/** Constructor. */
	public TransferReceiver(MIME mime, ContentDecoder decoder) {
		this.mime = mime;
		this.decoder = decoder;
	}
	
	protected MIME mime;
	protected ContentDecoder decoder;
	
	/** the returned work is true if the end has been reached. */
	public abstract AsyncWork<Boolean,IOException> consume(ByteBuffer buf);
	
	/** Return true if the transfer is not expected to be empty. */
	public abstract boolean isExpectingData();

}
