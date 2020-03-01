package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import net.lecousin.compression.gzip.GZipConsumer;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.BufferedAsyncConsumer;
import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.encoding.QuotedPrintable;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.data.Bytes;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.mime.header.MimeHeaders;

/**
 * Instantiate a ContentDecoder based on the Content-Encoding or Content-Transfer-Encoding header.
 * If none is specified, a default is used with any encoding.
 */
public final class ContentDecoderFactory {
	
	private ContentDecoderFactory() { /* no instance */ }

	private static Map<String, UnaryOperator<AsyncConsumer<ByteBuffer, IOException>>> decoders = new HashMap<>();
	
	static {
		registerDecoder("7bit", null);
		registerDecoder("8bit", null);
		registerDecoder("identity", null);
		registerDecoder("binary", null);
		registerDecoder("base64",
			next -> Base64Encoding.instance.new DecoderConsumer<IOException>(
				next.convert(Bytes.Readable::toByteBuffer),
				err -> new IOException("Error decoding base 64 MIME content", err)
			).convert(ByteArray::fromByteBuffer)
		);
		registerDecoder("quoted-printable",
			next -> new QuotedPrintable.DecoderConsumer<IOException>(
				next.convert(Bytes.Readable::toByteBuffer),
				8192,
				err -> new IOException("Error decoding quoted-printable MIME content", err)
			).convert(ByteArray::fromByteBuffer)
		);
		registerDecoder("gzip",
			next -> new GZipConsumer(8192, new BufferedAsyncConsumer<>(3, next))
		);
	}
	
	/** Register a ContentDecoder for a given Content-Encoding value. */
	public static void registerDecoder(
		String encoding, UnaryOperator<AsyncConsumer<ByteBuffer, IOException>> decoderSupplier
	) {
		encoding = encoding.toLowerCase();
		synchronized (decoders) {
			decoders.put(encoding, decoderSupplier);
		}
	}
	
	/** Return the list of registered encoding. */
	public static List<String> getSupportedEncoding() {
		return new ArrayList<>(decoders.keySet());
	}

	/** Create a ContentDecoder for the given Content-Encoding. */
	public static AsyncConsumer<ByteBuffer, IOException> createDecoder(AsyncConsumer<ByteBuffer, IOException> next, String encoding) {
		UnaryOperator<AsyncConsumer<ByteBuffer, IOException>> supplier = null;
		boolean hasDecoder = false;
		String elc = encoding.toLowerCase();
		synchronized (decoders) {
			if (decoders.containsKey(elc)) {
				hasDecoder = true;
				supplier = decoders.get(elc);
			}
		}
		if (!hasDecoder) {
			Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(ContentDecoderFactory.class);
			if (logger.error())
				logger.error("Content encoding '" + encoding + "' not supported, data may not be readable.");
		}
		if (supplier == null)
			return next;
		return supplier.apply(next);
	}

	/** Create a ContentDecoder for the Content-Encoding or Content-Transfer-Encoding field of the given MIME. */
	public static AsyncConsumer<ByteBuffer, IOException> createDecoder(AsyncConsumer<ByteBuffer, IOException> consumer, MimeHeaders headers) {
		LinkedList<String> encoding = new LinkedList<>();
		
		TransferEncodingFactory.encodingAndTransferFromHeader(headers, MimeHeaders.CONTENT_TRANSFER_ENCODING, encoding, null);
		TransferEncodingFactory.addEncodingFromHeader(headers, MimeHeaders.CONTENT_ENCODING, encoding);

		for (String coding : encoding)
			consumer = createDecoder(consumer, coding);
		
		return consumer;
	}
	
}
