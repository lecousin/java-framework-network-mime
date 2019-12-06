package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.util.LinkedList;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValues;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoderFactory;
import net.lecousin.framework.network.mime.transfer.encoding.IdentityDecoder;

/**
 * Instantiate a Transfer based on the Transfer-Encoding or Content-Transfer-Encoding header.
 * If none is specified, a default is used.
 */
public final class TransferEncodingFactory {

	private TransferEncodingFactory() { /* no instance */ }
	
	/** Instantiate a TransferReceiver with a ContentDecoder based on the Transfer-Encoding,
	 * Content-Transfer-Encoding and Content-Encoding headers. */
	@SuppressWarnings("squid:S3776") // complexity
	public static TransferReceiver create(MimeMessage mime, IO.Writable out) throws IOException {
		String transfer = IdentityTransfer.TRANSFER_NAME;
		LinkedList<String> encoding = new LinkedList<>();

		transfer = encodingAndTransferFromHeader(mime, MimeMessage.TRANSFER_ENCODING, encoding, transfer);
		transfer = encodingAndTransferFromHeader(mime, MimeMessage.CONTENT_TRANSFER_ENCODING, encoding, transfer);
		addEncodingFromHeader(mime, MimeMessage.CONTENT_ENCODING, encoding);
		
		ContentDecoder decoder = new IdentityDecoder(out);
		for (String coding : encoding)
			decoder = ContentDecoderFactory.createDecoder(decoder, coding);

		if (ChunkedTransfer.TRANSFER_NAME.equals(transfer))
			return new ChunkedTransfer(mime, decoder);
		return new IdentityTransfer(mime, decoder);
	}
	
	/** Add encoding from the given MIME header, remove and return any value which is a transfer. */
	public static String encodingAndTransferFromHeader(MimeMessage mime, String headerName, LinkedList<String> encoding, String defaultValue) {
		if (!addEncodingFromHeader(mime, headerName, encoding))
			return defaultValue;
		String s = encoding.getLast();
		if (IdentityTransfer.TRANSFER_NAME.equals(s))
			encoding.removeLast();
		else if (ChunkedTransfer.TRANSFER_NAME.equals(s)) {
			encoding.removeLast();
			return s;
		}
		return defaultValue;
	}
	
	/** Add encoding from the given MIME header. */
	public static boolean addEncodingFromHeader(MimeMessage mime, String headerName, LinkedList<String> encoding) {
		ParameterizedHeaderValues values;
		try { values = mime.getFirstHeaderValue(headerName, ParameterizedHeaderValues.class); }
		catch (Exception e) { values = null; }
		if (values == null) return false;
		boolean changed = false;
		for (ParameterizedHeaderValue value : values.getValues()) {
			String e = value.getMainValue();
			if (e == null) continue;
			e = e.trim().toLowerCase();
			if (e.isEmpty()) continue;
			encoding.add(e);
			changed = true;
		}
		return changed;
	}

}
