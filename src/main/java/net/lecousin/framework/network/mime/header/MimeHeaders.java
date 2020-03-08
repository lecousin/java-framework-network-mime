package net.lecousin.framework.network.mime.header;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.collections.LinkedArrayList;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.io.data.Bytes;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.text.ByteArrayStringIso8859;
import net.lecousin.framework.text.ByteArrayStringIso8859Buffer;
import net.lecousin.framework.text.CharArrayString;
import net.lecousin.framework.text.IString;
import net.lecousin.framework.util.Runnables.ConsumerThrows;

/** MIME headers container. */
public class MimeHeaders {
	
	/** Constructor. */
	public MimeHeaders() {
	}
	
	/** Constructor. */
	public MimeHeaders(List<MimeHeader> headers) {
		this.headers.addAll(headers);
	}

	/** Constructor. */
	public MimeHeaders(MimeHeader... headers) {
		for (MimeHeader h : headers)
			this.headers.add(h);
	}
	
	// ***** Headers *****
	
	private LinkedArrayList<MimeHeader> headers = new LinkedArrayList<>(10);
	
	public List<MimeHeader> getHeaders() {
		return headers;
	}
	
	/** Return the list of headers with the given name (case insensitive). */
	public List<MimeHeader> getList(String name) {
		name = name.toLowerCase();
		ArrayList<MimeHeader> list = new ArrayList<>();
		for (MimeHeader h : headers)
			if (h.getNameLowerCase().equals(name))
				list.add(h);
		return list;
	}
	
	/** Return the list of headers values with the given name (case insensitive), parsed into the requested format. */
	public <T extends HeaderValueFormat> List<T> getValues(String name, Class<T> format) throws MimeException {
		List<T> list = new LinkedList<>();
		name = name.toLowerCase();
		for (MimeHeader h : headers)
			if (h.getNameLowerCase().equals(name))
				list.add(h.getValue(format));
		return list;
	}
	
	/** Return the first header with the given name (case insensitive) or null. */
	public MimeHeader getFirst(String name) {
		name = name.toLowerCase();
		for (MimeHeader h : headers)
			if (h.getNameLowerCase().equals(name))
				return h;
		return null;
	}
	
	/** Return the value of the first header with the given name (case insensitive) parsed into the requested format, or null. */
	public <T extends HeaderValueFormat> T getFirstValue(String name, Class<T> format) throws MimeException {
		MimeHeader h = getFirst(name);
		if (h == null)
			return null;
		return h.getValue(format);
	}
	
	/** Return the value of the first header with the given name (case insensitive), or null. */
	public String getFirstRawValue(String name) {
		MimeHeader h = getFirst(name);
		if (h == null)
			return null;
		return h.getRawValue();
	}
	
	/** Return the value of the first header with the given name (case insensitive) parsed into a Long, or null. */
	public Long getFirstLongValue(String name) {
		MimeHeader h = getFirst(name);
		if (h == null)
			return null;
		try {
			return Long.valueOf(h.getRawValue());
		} catch (Exception e) {
			return null;
		}
	}
	
	/** Return true if thie message contains at least one header with the given name (case insensitive). */
	public boolean has(String name) {
		return getFirst(name) != null;
	}
	
	/** Append a header. */
	public MimeHeaders addRawValue(String name, String rawValue) {
		headers.add(new MimeHeader(name, rawValue));
		return this;
	}
	
	/** Append a header. */
	public MimeHeaders add(String name, HeaderValueFormat value) {
		headers.add(new MimeHeader(name, value));
		return this;
	}
	
	/** Append a header. */
	public MimeHeaders add(MimeHeader header) {
		headers.add(header);
		return this;
	}

	/** Remove any header with the same name, and append this new header. */
	public MimeHeaders setRawValue(String name, String rawValue) {
		remove(name);
		addRawValue(name, rawValue);
		return this;
	}
	
	/** Remove any header with the same name, and append this new header. */
	public MimeHeaders set(String name, HeaderValueFormat value) {
		remove(name);
		add(name, value);
		return this;
	}
	
	/** Remove any header with the same name, and append this new header. */
	public MimeHeaders set(MimeHeader header) {
		remove(header.getName());
		add(header);
		return this;
	}
	
	/** Remove any header with the given name. */
	public MimeHeaders remove(String name) {
		name = name.toLowerCase();
		for (Iterator<MimeHeader> it = headers.iterator(); it.hasNext(); )
			if (it.next().getNameLowerCase().equals(name))
				it.remove();
		return this;
	}
	
	/** Generate headers into the given string. */
	public void appendTo(IString s) {
		for (MimeHeader h : headers)
			h.appendTo(s);
	}
	
	// *** Common headers ***
	
	public static final String CONTENT_TYPE = "Content-Type";
	public static final String CONTENT_LENGTH = "Content-Length";
	public static final String CONTENT_DISPOSITION = "Content-Disposition";
	public static final String TRANSFER_ENCODING = "Transfer-Encoding";
	public static final String CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";
	public static final String CONTENT_ENCODING = "Content-Encoding";
	
	public Long getContentLength() {
		return getFirstLongValue(CONTENT_LENGTH);
	}
	
	/** Set the Content-Length header. */
	public MimeHeaders setContentLength(long size) {
		setRawValue(CONTENT_LENGTH, Long.toString(size));
		return this;
	}

	/** Parse the Content-Type header and return it, or null if it is not present. */
	public ParameterizedHeaderValue getContentType() throws MimeException {
		return getFirstValue(CONTENT_TYPE, ParameterizedHeaderValue.class);
	}
	
	/** Parse the Content-Type header and return its main value, or null if it is not present. */
	public String getContentTypeValue() {
		try {
			ParameterizedHeaderValue h = getContentType();
			if (h == null)
				return null;
			return h.getMainValue();
		} catch (Exception e) {
			// ignore
			return null;
		}
	}
	
	
	/** Generate this MimeHeaders into a byte array string buffer. */
	public ByteArrayStringIso8859Buffer generateString(int bufferSize) {
		ByteArrayStringIso8859Buffer s = new ByteArrayStringIso8859Buffer(new ByteArrayStringIso8859(bufferSize));
		s.setNewArrayStringCapacity(bufferSize);
		generateString(s);
		return s;
	}
	
	/** Generate this MimeHeaders into a byte array string buffer. */
	public void generateString(ByteArrayStringIso8859Buffer s) {
		appendTo(s);
		s.append("\r\n");
	}
	
	/** Create a consumer for headers. */
	public HeadersConsumer createConsumer() {
		return new HeadersConsumer();
	}
	
	/** Create a consumer for headers. */
	public HeadersConsumer createConsumer(int maximumLength) {
		return new HeadersConsumer(maximumLength);
	}
	
	/** Consume bytes to parse headers. */
	public class HeadersConsumer implements PartialAsyncConsumer<Bytes.Readable, MimeException> {
		/** Constructor. */
		public HeadersConsumer() {
			this(-1);
		}
		
		/** Constructor. */
		public HeadersConsumer(int maximumLength) {
			Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(getClass());
			newLineConsumer = data -> {
				while (data.hasRemaining()) {
					if (maximumLength != -1 && ++length > maximumLength)
						throw new MimeException("Maximum header length reached");
					byte b = data.get();
					switch (b) {
					case '\r': break;
					case '\n':
						if (name != null) {
							MimeHeader h = new MimeHeader(name.asString(), value.trim().asString());
							headers.add(h);
							if (logger.debug())
								logger.debug("Header line found: " + h.getName() + ": " + h.getRawValue());
						}
						if (logger.debug())
							logger.debug("End of headers");
						end = true;
						return;
					case ':':
						throw new MimeException("Empty header name");
					case ' ': case '\t':
						if (value == null) throw new MimeException("First header line cannot start with a space");
						consumer = valueConsumer;
						consumer.accept(data);
						return;
					default:
						if (name != null) {
							MimeHeader h = new MimeHeader(name.asString(), value.trim().asString());
							headers.add(h);
							if (logger.debug())
								logger.debug("Header line found: " + h.getName() + ": " + h.getRawValue());
						}
						name = new CharArrayString(32);
						name.append((char)(b & 0xFF));
						consumer = nameConsumer;
						consumer.accept(data);
						return;
					}
				}
			};
			
			nameConsumer = data -> {
				while (data.hasRemaining()) {
					if (maximumLength != -1 && ++length > maximumLength)
						throw new MimeException("Maximum header length reached");
					byte b = data.get();
					switch (b) {
					case ':':
						value = new CharArrayString(64);
						consumer = valueConsumer;
						consumer.accept(data);
						return;
					case '\r': break;
					case '\n':
						throw new MimeException("Header line must contain a ':' <" + name.asString() + ">");
					default:
						name.append((char)(b & 0xFF));
						break;
					}
				}
			};
			
			valueConsumer = data -> {
				while (data.hasRemaining()) {
					if (maximumLength != -1 && ++length > maximumLength)
						throw new MimeException("Maximum header length reached");
					byte b = data.get();
					switch (b) {
					case '\r': break;
					case '\n':
						consumer = newLineConsumer;
						consumer.accept(data);
						return;
					default:
						value.append((char)(b & 0xFF));
						break;
					}
				}
			};
			
			consumer = newLineConsumer;
		}
		
		private int length = 0;
		private CharArrayString name;
		private CharArrayString value;
		private boolean end = false;
		private ConsumerThrows<Bytes.Readable, MimeException> consumer;
		
		private ConsumerThrows<Bytes.Readable, MimeException> newLineConsumer;
		private ConsumerThrows<Bytes.Readable, MimeException> nameConsumer;
		private ConsumerThrows<Bytes.Readable, MimeException> valueConsumer;
		
		@Override
		public AsyncSupplier<Boolean, MimeException> consume(Bytes.Readable data) {
			try {
				consumer.accept(data);
				return new AsyncSupplier<>(Boolean.valueOf(end), null);
			} catch (MimeException e) {
				return new AsyncSupplier<>(null, e);
			}
		}
		
		@Override
		public boolean isExpectingData() {
			return !end;
		}
		
	}

}
