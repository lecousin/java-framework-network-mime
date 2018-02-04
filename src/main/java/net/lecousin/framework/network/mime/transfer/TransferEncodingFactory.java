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
	public static TransferReceiver create(MimeMessage mime, IO.Writable out) throws IOException {
		String transfer = "identity";
		LinkedList<String> encoding = new LinkedList<>();

		ParameterizedHeaderValues values;
		try { values = mime.getFirstHeaderValue(MimeMessage.TRANSFER_ENCODING, ParameterizedHeaderValues.class); }
		catch (Exception e) { values = null; }
		if (values != null) {
			for (ParameterizedHeaderValue value : values.getValues()) {
				String e = value.getMainValue();
				if (e == null) continue;
				e = e.trim().toLowerCase();
				if (e.isEmpty()) continue;
				encoding.add(e);
			}
			if (!encoding.isEmpty()) {
				String s = encoding.getLast();
				if ("identity".equals(s))
					encoding.removeLast();
				else if ("chunked".equals(s)) {
					transfer = s;
					encoding.removeLast();
				}
			}
		}
		
		try { values = mime.getFirstHeaderValue(MimeMessage.CONTENT_TRANSFER_ENCODING, ParameterizedHeaderValues.class); }
		catch (Exception e) { values = null; }
		if (values != null) {
			for (ParameterizedHeaderValue value : values.getValues()) {
				String e = value.getMainValue();
				if (e == null) continue;
				e = e.trim().toLowerCase();
				if (e.isEmpty()) continue;
				encoding.add(e);
			}
			if (!encoding.isEmpty()) {
				String s = encoding.getLast();
				if ("identity".equals(s))
					encoding.removeLast();
				else if ("chunked".equals(s)) {
					transfer = s;
					encoding.removeLast();
				}
			}
		}
		
		try { values = mime.getFirstHeaderValue(MimeMessage.CONTENT_ENCODING, ParameterizedHeaderValues.class); }
		catch (Exception e) { values = null; }
		if (values != null) {
			for (ParameterizedHeaderValue value : values.getValues()) {
				String e = value.getMainValue();
				if (e == null) continue;
				e = e.trim().toLowerCase();
				if (e.isEmpty()) continue;
				encoding.add(e);
			}
		}
		
		ContentDecoder decoder = new IdentityDecoder(out);
		for (String coding : encoding)
			decoder = ContentDecoderFactory.createDecoder(decoder, coding);

		if ("chunked".equals(transfer))
			return new ChunkedTransfer(mime, decoder);
		return new IdentityTransfer(mime, decoder);
	}

}
