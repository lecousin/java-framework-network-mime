package net.lecousin.framework.network.mime;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.AsyncWork.AsyncWorkListener;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.encoding.Base64;
import net.lecousin.framework.io.encoding.QuotedPrintable;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.network.mime.transfer.TransferReceiver;
import net.lecousin.framework.util.Pair;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

// skip checkstyle: AbbreviationAsWordInName
/**
 * MIME message, with header and optional body.
 */
public class MIME {
	
	public static final Log logger = LogFactory.getLog(MIME.class);
	
	public static final byte[] CRLF = new byte[] { '\r', '\n' };
	
	public static final String CONTENT_TYPE = "Content-Type";
	public static final String CONTENT_LENGTH = "Content-Length";
	public static final String TRANSFER_ENCODING = "Transfer-Encoding";
	public static final String CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";
	public static final String CONTENT_ENCODING = "Content-Encoding";
	public static final String CONNECTION = "Connection";

	private HashMap<String,List<String>> header = new HashMap<>(10);
	private String lastFieldName = null;
	
	/** Append a line to the header. It parses the line and add it to the list of header values. */
	public void appendHeaderLine(String line) {
		if (line.isEmpty()) return;
		char c = line.charAt(0);
		if (c == ' ' || c == '\t') {
			if (lastFieldName == null) {
				if (logger.isErrorEnabled())
					logger.error("Invalid MIME Header line (start with space, but no previous field): " + line);
				return;
			}
			List<String> list = header.get(lastFieldName);
			String lastValue = list.get(list.size() - 1);
			lastValue += line.trim();
			list.set(list.size() - 1, lastValue);
			return;
		}
		int i = line.indexOf(':');
		if (i < 0) {
			if (logger.isErrorEnabled())
				logger.error("Invalid MIME Header line: " + line);
			return;
		}
		String name = line.substring(0,i).trim().toLowerCase();
		String value = line.substring(i + 1).trim();
		List<String> list = header.get(name);
		if (list == null) {
			list = new ArrayList<String>();
			header.put(name, list);
		}
		list.add(value);
		return;
	}
	
	/** Return the list of values for the given header field, or null if no value. */
	public List<String> getHeaderValues(String field) {
		return header.get(field.toLowerCase());
	}
	
	/** Return the first value for the given header field, or null if no value. */
	public String getHeaderSingleValue(String field) {
		List<String> list = header.get(field.toLowerCase());
		if (list == null) return null;
		return list.get(0);
	}
	
	/** Return the first value as long for the given header field, or null if no value or not a long value. */
	public Long getHeaderSingleValueLong(String field) {
		String s = getHeaderSingleValue(field);
		if (s == null) return null;
		try {
			return Long.valueOf(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}
	
	/** Split the first value of the given header field. */
	public String[] getHeaderCommaSeparatedValues(String header) {
		String h = getHeaderSingleValue(header);
		if (h == null) return null;
		h = h.trim();
		if (h.length() == 0) return new String[0];
		String[] list = h.split(",");
		for (int i = 0; i < list.length; ++i)
			list[i] = list[i].trim();
		return list;
	}
	
	/** Check if the given header field, which is a comma separated field, contains the given value. */
	public boolean isHeaderCommaSeparatedContainingValue(String header, String value) {
		String[] values = getHeaderCommaSeparatedValues(header);
		if (values == null) return false;
		for (String v : values)
			if (v.toLowerCase().equals(value.toLowerCase()))
				return true;
		return false;
	}
	
	/** Decode a header content using RFC 2047, which specifies encoded word as follows:
	 * encoded-word = "=?" charset "?" encoding "?" encoded-text "?=".
	 */
	public static String decodeHeaderRFC2047(String value) throws IOException {
		int pos = 0;
		while (pos < value.length()) {
			int i = value.indexOf("=?", pos);
			int i2 = value.indexOf('"', pos);
			if (i2 >= 0 && (i < 0 || i > i2)) {
				int j = value.indexOf('"', i2 + 1);
				if (j > 0) {
					value = value.substring(0, i2) + value.substring(i2 + 1, j) + value.substring(j + 1);
					continue;
				}
			}
			if (i < 0) break;
			int j = value.indexOf("?=", i + 2);
			if (j < 0) break;
			String decoded = decodeRFC2047Word(value.substring(i + 2, j));
			value = value.substring(0, i) + decoded + value.substring(j + 2);
			pos = i + decoded.length();
		}
		return value;
	}

	/** Decode a word based on RFC 2047 specification. */
	public static String decodeRFC2047Word(String encodedWord) throws UnsupportedEncodingException, IOException {
		int i = encodedWord.indexOf('?');
		if (i < 0) return encodedWord;
		String charsetName = encodedWord.substring(0, i);
		encodedWord = encodedWord.substring(i + 1);
		i = encodedWord.indexOf('?');
		if (i < 0) return encodedWord;
		String encoding = encodedWord.substring(0, i);
		encodedWord = encodedWord.substring(i + 1);
		encoding = encoding.trim().toUpperCase();
		if ("B".equals(encoding)) {
			byte[] decoded = Base64.decode(encodedWord);
			return new String(decoded, charsetName);
		} else if ("Q".equals(encoding)) {
			ByteBuffer decoded = QuotedPrintable.decode(encodedWord);
			return new String(decoded.array(), 0, decoded.remaining(), charsetName);
		} else {
			throw new UnsupportedEncodingException("RFC 2047 encoding " + encoding);
		}
	}
	
	/** Encode a header parameter value, taking bytes in UTF-8,
	 * and depending on its content it may be directly returned,
	 * it may use double-quote or it may use the RFC 2047 encoding. */
	public static String encodeUTF8HeaderParameterValue(String value) {
		return encodeHeaderParameterValue(value, StandardCharsets.UTF_8);
	}

	/** Encode a header parameter value, taking bytes in the given charset,
	 * and depending on its content it may be directly returned,
	 * it may use double-quote or it may use the RFC 2047 encoding. */
	public static String encodeHeaderParameterValue(String value, Charset charset) {
		byte[] bytes = value.getBytes(charset);
		boolean hasSpecialChars = false;
		for (int i = 0; i < bytes.length; ++i)
			if (bytes[i] < 32 || bytes[i] > 126) {
				hasSpecialChars = true;
				break;
			}
		if (!hasSpecialChars) {
			return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
		StringBuilder s = new StringBuilder(value.length() + 64);
		s.append("=?UTF-8?B?");
		s.append(new String(Base64.encodeBase64(bytes)));
		s.append("?=");
		return s.toString();
	}
	
	/** Parse the given header field, using syntax "value; paramName=paramValue; anotherParam=anotherValue". */
	public Pair<String, Map<String, String>> parseParameterizedHeaderSingleValue(String headerName) throws IOException {
		String s = getHeaderSingleValue(headerName);
		if (s == null) return null;
		int i = s.indexOf(';');
		if (i < 0)
			return new Pair<>(s.trim(), new HashMap<>());
		String firstValue = s.substring(0, i).trim();
		firstValue = decodeHeaderRFC2047(firstValue);
		s = s.substring(i + 1);
		boolean inQuote = false;
		boolean inValue = false;
		Map<String, String> values = new HashMap<>();
		StringBuilder name = new StringBuilder();
		StringBuilder value = new StringBuilder();
		for (i = 0; i < s.length(); ++i) {
			char c = s.charAt(i);
			if (inQuote) {
				if (c == '\\') {
					if (i < s.length() - 1)
						c = s.charAt(++i);
				} else if (c == '"') {
					inQuote = false;
					continue;
				}
			} else {
				if (!inValue && c == '=') {
					inValue = true;
					continue;
				}
				if (c == ';') {
					values.put(name.toString(), decodeHeaderRFC2047(value.toString()));
					name = new StringBuilder();
					value = new StringBuilder();
					inValue = false;
					continue;
				}
				if (c == ' ' && !inValue && name.length() == 0)
					continue;
				if (c == '"') {
					inQuote = true;
					continue;
				}
			}
			if (inValue) value.append(c);
			else name.append(c);
		}
		if (name.length() > 0 || value.length() > 0)
			values.put(name.toString(), decodeHeaderRFC2047(value.toString()));
		return new Pair<>(firstValue, values);
	}
	
	/** Return the value of the Content-Length header field, or null. */
	public Long getContentLength() {
		return getHeaderSingleValueLong(CONTENT_LENGTH);
	}
	
	/** Set the Content-Length header field. */
	public void setContentLength(long length) {
		setHeader(CONTENT_LENGTH, Long.toString(length));
	}
	
	/** Get the Content-Type header field. */
	public String getContentType() {
		return getHeaderSingleValue(CONTENT_TYPE);
	}
	
	/** Parse the Content-Type header field, and return the type together with parameters. */
	public Pair<String, Map<String, String>> parseContentType() throws IOException {
		return parseParameterizedHeaderSingleValue(CONTENT_TYPE);
	}
	
	/** Return true if the given header field exists. */
	public boolean hasHeader(String name) {
		List<String> list = header.get(name.toLowerCase());
		return list != null && !list.isEmpty();
	}
	
	/** Set a header field to a single value. */
	public void setHeader(String name, String value) {
		List<String> list = new ArrayList<String>(1);
		list.add(value);
		header.put(name.toLowerCase(), list);
	}
	
	/** Add a value to a header field. */
	public void addHeaderValue(String name, String value) {
		name = name.toLowerCase();
		List<String> list = header.get(name);
		if (list == null) {
			list = new ArrayList<String>(5);
			header.put(name, list);
		}
		list.add(value);
	}
	
	/** Return the headers in a map between header fields and their values. */
	public Map<String,List<String>> getHeaders() {
		return header;
	}
	
	/** Return a list of headers. */
	public List<Pair<String,String>> getHeadersList() {
		ArrayList<Pair<String,String>> list = new ArrayList<>(header.size());
		for (Map.Entry<String,List<String>> h : header.entrySet()) {
			for (String value : h.getValue()) {
				list.add(new Pair<>(h.getKey(), value));
			}
		}
		return list;
	}
	
	/** Generate a string for the header fields. */
	public String generateHeaders() {
		return generateHeaders(false);
	}
	
	/** Generate a string for the header fields with an optional empty line. */
	public String generateHeaders(boolean addFinalCRLF) {
		StringBuilder s = new StringBuilder();
		generateHeaders(s, addFinalCRLF);
		return s.toString();
	}

	/** Generate a string for the header fields with an optional empty line. */
	public void generateHeaders(StringBuilder s, boolean addFinalCRLF) {
		for (Map.Entry<String, List<String>> h : header.entrySet()) {
			String name = h.getKey();
			int pos = -1;
			do {
				name = name.substring(0, pos + 1) + Character.toUpperCase(name.charAt(pos + 1)) + name.substring(pos + 2);
				pos = name.indexOf('-', pos + 1);
			} while (pos > 0 && pos < name.length());
			for (String value : h.getValue())
				s.append(name).append(": ").append(value).append("\r\n");
		}
		if (addFinalCRLF) s.append("\r\n");
	}
	
	// --- Body for reception ---
	
	private IO.Writable bodyOut = null;
	private TransferReceiver bodyTransfer = null;
	
	/**
	 * Prepare to receive the body and save it to the given output. The transfer is instantiated by using
	 * {@link TransferEncodingFactory#create} method, which uses the Transfer-Encoding,
	 * Content-Transfer-Encoding and Content-Encoding headers to decode data.
	 * It returns true if no data is expected, false if data is expected.
	 */
	public <T extends IO.Writable & IO.Readable> boolean initBodyTransfer(T output) throws IOException {
		bodyOut = output;
		bodyTransfer = TransferEncodingFactory.create(this, output);
		if (logger.isDebugEnabled())
			logger.debug("Body transfer initialized with " + bodyTransfer + ", something to read: " + (bodyTransfer.isExpectingData()));
		return !bodyTransfer.isExpectingData();
	}
	
	/**
	 * Receive some data of the body, using the transfer initialized by the method
	 * {@link #initBodyTransfer(net.lecousin.framework.io.IO.Writable)}.
	 */
	public AsyncWork<Boolean, IOException> bodyDataReady(ByteBuffer data) {
		if (logger.isDebugEnabled())
			logger.debug("Body data ready, consume it: " + data.remaining() + " bytes");
		return bodyTransfer.consume(data);
	}
	
	/** Return the body previously set by {@link #initBodyTransfer(net.lecousin.framework.io.IO.Writable)}. */
	public IO.Writable getBodyOutput() {
		return bodyOut;
	}
	/** Return the body previously set by {@link #initBodyTransfer(net.lecousin.framework.io.IO.Writable)}. */
	public IO.Readable getBodyOutputAsInput() {
		return (IO.Readable)bodyOut;
	}
	
	// --- Body to be sent ---
	
	private IO.Readable bodyIn = null;
	
	/** Return the body to send, previoulsy set by setBodyToSend. */
	public IO.Readable getBodyInput() {
		return bodyIn;
	}
	
	/** Set the body to send. */
	public void setBodyToSend(IO.Readable body) {
		this.bodyIn = body;
	}
	
	/** Receive header lines from the given client. */
	public SynchronizationPoint<IOException> readHeader(TCPClient client, int timeout) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		if (logger.isDebugEnabled())
			logger.debug("Receiving header lines...");
		AsyncWork<ByteArrayIO,IOException> line = client.getReceiver().readUntil((byte)'\n', 1024, timeout);
		line.listenInline(new AsyncWorkListener<ByteArrayIO, IOException>() {
			@Override
			public void ready(ByteArrayIO line) {
				String s = line.getAsString(StandardCharsets.US_ASCII);
				if (logger.isDebugEnabled())
					logger.debug("Header line received: " + s);
				int i = s.indexOf('\r');
				if (i >= 0) s = s.substring(0,i);
				i = s.indexOf('\n');
				if (i >= 0) s = s.substring(0,i);
				if (s.length() == 0) {
					if (logger.isDebugEnabled())
						logger.debug("End of header lines");
					result.unblock();
					return;
				}
				appendHeaderLine(s);
				client.getReceiver().readUntil((byte)'\n', 1024, timeout).listenInline(this);
			}
			
			@Override
			public void error(IOException error) {
				result.error(error);
			}
			
			@Override
			public void cancelled(CancelException event) {
				result.cancel(event);
			}
		});
		return result;
	}
	
}
