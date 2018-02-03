package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.mime.MimeMessage;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValues;
import net.lecousin.framework.network.mime.transfer.encoding.Base64Decoder;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;
import net.lecousin.framework.network.mime.transfer.encoding.GZipDecoder;
import net.lecousin.framework.network.mime.transfer.encoding.IdentityDecoder;
import net.lecousin.framework.network.mime.transfer.encoding.QuotedPrintableDecoder;

/**
 * Instantiate a Transfer based on the Transfer-Encoding or Content-Trasnfer-Encoding header.
 * If none is specified, a default is used.
 */
public final class TransferEncodingFactory {

	private TransferEncodingFactory() { /* no instance */ }
	
	private static Map<String, Constructor<? extends ContentDecoder>> decoders = new HashMap<>();
	
	static {
		try {
			registerDecoder("7bit", null);
			registerDecoder("8bit", null);
			registerDecoder("identity", null);
			registerDecoder("base64", Base64Decoder.class);
			registerDecoder("quoted-printable", QuotedPrintableDecoder.class);
			registerDecoder("gzip", GZipDecoder.class);
		} catch (NoSuchMethodException e) {
			// not possible
		}
	}
	
	/** Register a ContentDecoder for a given Content-Encoding value. */
	public static void registerDecoder(String encoding, Class<? extends ContentDecoder> decoderClass) throws NoSuchMethodException {
		encoding = encoding.toLowerCase();
		Constructor<? extends ContentDecoder> ctor = decoderClass == null ? null : decoderClass.getConstructor(ContentDecoder.class);
		synchronized (decoders) {
			decoders.put(encoding, ctor);
		}
	}
	
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
			decoder = createDecoder(decoder, coding);

		if ("chunked".equals(transfer))
			return new ChunkedTransfer(mime, decoder);
		return new IdentityTransfer(mime, decoder);
	}

	/** Create a ContentDecoder for the given Content-Encoding. */
	public static ContentDecoder createDecoder(ContentDecoder next, String encoding) {
		Constructor<? extends ContentDecoder> ctor = null;
		boolean hasDecoder = false;
		String elc = encoding.toLowerCase();
		synchronized (decoders) {
			if (decoders.containsKey(elc)) {
				hasDecoder = true;
				ctor = decoders.get(elc);
			}
		}
		if (!hasDecoder) {
			if (MimeMessage.logger.isErrorEnabled())
				MimeMessage.logger.error("Content encoding '" + encoding
					+ "' not supported, data may not be readable.");
		}
		if (ctor == null)
			return next;
		try {
			return ctor.newInstance(next);
		} catch (Exception e) {
			if (MimeMessage.logger.isErrorEnabled())
				MimeMessage.logger.error("Content decoder " + ctor.getName() + " cannot be instantiated for encoding '"
					+ encoding + "', data may not be readable.");
			return next;
		}
	}
	
	/** Create a ContentDecoder for the Content-Encoding or Content-Transfer-Encoding field of the given MIME. */
	public static ContentDecoder createDecoder(ContentDecoder next, MimeMessage mime) {
		LinkedList<String> encoding = new LinkedList<>();
		
		ParameterizedHeaderValues values;
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

		for (String coding : encoding)
			next = createDecoder(next, coding);
		
		return next;
	}
	
}
