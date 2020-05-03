package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValues;

/**
 * Instantiate a Transfer based on the Transfer-Encoding or Content-Transfer-Encoding header.
 * If none is specified, a default is used.
 */
public final class TransferEncodingFactory {

	private TransferEncodingFactory() {
		/* no instance */
	}
	
	/** Instantiate a PartialAsyncConsumer with a ContentDecoder based on the Transfer-Encoding,
	 * Content-Transfer-Encoding and Content-Encoding headers. 
	 * @throws MimeException if headers are invalid
	 */
	public static PartialAsyncConsumer<ByteBuffer, IOException>
	create(MimeHeaders headers, AsyncConsumer<ByteBuffer, IOException> consumer) throws IOException, MimeException {
		String transfer = IdentityTransfer.TRANSFER_NAME;
		LinkedList<String> encoding = new LinkedList<>();

		transfer = encodingAndTransferFromHeader(headers, MimeHeaders.TRANSFER_ENCODING, encoding, transfer);
		transfer = encodingAndTransferFromHeader(headers, MimeHeaders.CONTENT_TRANSFER_ENCODING, encoding, transfer);
		addEncodingFromHeader(headers, MimeHeaders.CONTENT_ENCODING, encoding);
		
		AsyncConsumer<ByteBuffer, IOException> decoder = consumer;
		for (String coding : encoding)
			decoder = ContentDecoderFactory.createDecoder(decoder, coding);

		if (ChunkedTransfer.TRANSFER_NAME.equals(transfer))
			return new ChunkedTransfer.Receiver(headers, decoder);
		return new IdentityTransfer.Receiver(headers, decoder);
	}
	
	/** Add encoding from the given MIME header, remove and return any value which is a transfer. 
	 * @throws MimeException if headers are invalid
	 */
	@SuppressWarnings("java:S1319") // we want LinkedList
	public static String encodingAndTransferFromHeader(MimeHeaders headers, String headerName, LinkedList<String> encoding, String defaultValue)
	throws MimeException {
		if (!addEncodingFromHeader(headers, headerName, encoding))
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
	
	/** Add encoding from the given MIME header. 
	 * @throws MimeException if headers are invalid
	 */
	@SuppressWarnings("java:S1319") // we want LinkedList
	public static boolean addEncodingFromHeader(MimeHeaders headers, String headerName, LinkedList<String> encoding) throws MimeException {
		List<MimeHeader> list = headers.getList(headerName);
		if (list.isEmpty())
			return false;
		if (list.size() > 1)
			throw new MimeException("Header " + headerName + " must be set once, found: " + list.size());
		ParameterizedHeaderValues values = list.get(0).getValue(ParameterizedHeaderValues.class);
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
