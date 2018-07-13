package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;

/**
 * MIME Transfer, based on the Transfer-Encoding or Content-Transfer-Encoding header.
 */
public abstract class TransferReceiver {

	/** Constructor. */
	public TransferReceiver(MimeMessage mime, ContentDecoder decoder) {
		this.mime = mime;
		this.decoder = decoder;
	}
	
	protected MimeMessage mime;
	protected ContentDecoder decoder;
	
	/** the returned work is true if the end has been reached. */
	public abstract AsyncWork<Boolean,IOException> consume(ByteBuffer buf);
	
	/** Return true if the transfer is not expected to be empty. */
	public abstract boolean isExpectingData();

}
