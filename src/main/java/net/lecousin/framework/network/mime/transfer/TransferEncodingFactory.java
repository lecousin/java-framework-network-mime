package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.mime.MIME;
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
	public static TransferReceiver create(MIME mime, IO.Writable out) throws IOException {
		String transfer = "identity";
		LinkedList<String> encoding = new LinkedList<>();

		String s = mime.getHeaderSingleValue(MIME.TRANSFER_ENCODING);
		if (s != null) {
			String[] list = s.split(",");
			for (String e : list) {
				e = e.trim().toLowerCase();
				if (e.isEmpty()) continue;
				encoding.add(e);
			}
			if (!encoding.isEmpty()) {
				s = encoding.getLast();
				if ("identity".equals(s))
					encoding.removeLast();
				else if ("chunked".equals(s)) {
					transfer = s;
					encoding.removeLast();
				}
			}
		}
		
		s = mime.getHeaderSingleValue(MIME.CONTENT_TRANSFER_ENCODING);
		if (s != null) {
			String[] list = s.split(",");
			for (String e : list) {
				e = e.trim().toLowerCase();
				if (e.isEmpty()) continue;
				encoding.add(e);
			}
			if (!encoding.isEmpty()) {
				s = encoding.getLast();
				if ("identity".equals(s))
					encoding.removeLast();
				else if ("chunked".equals(s)) {
					transfer = s;
					encoding.removeLast();
				}
			}
		}
		
		s = mime.getHeaderSingleValue(MIME.CONTENT_ENCODING);
		if (s != null) {
			String[] list = s.split(",");
			for (String e : list) {
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
			if (MIME.logger.isErrorEnabled())
				MIME.logger.error("Content encoding '" + encoding
					+ "' not supported, data may not be readable.");
		}
		if (ctor == null)
			return next;
		try {
			return ctor.newInstance(next);
		} catch (Exception e) {
			if (MIME.logger.isErrorEnabled())
				MIME.logger.error("Content decoder " + ctor.getName() + " cannot be instantiated for encoding '"
					+ encoding + "', data may not be readable.");
			return next;
		}
	}
	
	// TODO add other common types of transfer
	
}
