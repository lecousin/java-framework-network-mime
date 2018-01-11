package net.lecousin.framework.network.mime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.AsyncWork.AsyncWorkListener;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.network.mime.transfer.IdentityTransfer;
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
	
	/** Parse the given header field, using syntax "value; paramName=paramValue; anotherParam=anotherValue". */
	public Pair<String, Map<String, String>> parseParameterizedHeaderSingleValue(String headerName) throws IOException {
		return MIMEUtil.parseParameterizedHeader(getHeaderSingleValue(headerName));
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
	 * True is returned if the end of the body has been reached.
	 */
	public AsyncWork<Boolean, IOException> bodyDataReady(ByteBuffer data) {
		if (logger.isTraceEnabled())
			logger.trace("Body data ready, consume it: " + data.remaining() + " bytes");
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
	
	// --- Receive ---
	
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
	
	// --- Send ---
	
	/** Send this MIME to the given TCP connection. */
	public ISynchronizationPoint<IOException> send(TCPRemote remote) {
		if (bodyIn == null) {
			if (logger.isDebugEnabled())
				logger.debug("Sending headers without body to " + remote);
			setContentLength(0);
			byte[] headers = generateHeaders(true).getBytes(StandardCharsets.US_ASCII);
			return remote.send(ByteBuffer.wrap(headers));
		}
		if ((bodyIn instanceof IO.KnownSize) && !"chunked".equals(getHeaderSingleValue(MIME.TRANSFER_ENCODING))) {
			SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
			((IO.KnownSize)bodyIn).getSizeAsync().listenInline((size) -> {
				new Task.Cpu.FromRunnable("Send MIME to " + remote, bodyIn.getPriority(), () -> {
					if (logger.isDebugEnabled())
						logger.debug("Sending headers with body of " + size.longValue() + " to " + remote);
					setContentLength(size.longValue());
					byte[] headers = generateHeaders(true).getBytes(StandardCharsets.US_ASCII);
					ISynchronizationPoint<IOException> sendHeaders = remote.send(ByteBuffer.wrap(headers));
					sendHeaders.listenInline(() -> {
						if (bodyIn instanceof IO.Readable.Buffered)
							IdentityTransfer.send(remote, (IO.Readable.Buffered)bodyIn).listenInline(sp);
						else
							IdentityTransfer.send(remote, bodyIn, 65536, 3).listenInline(sp);
					}, sp);
				}).start();
			}, sp);
			return sp;
		}
		if (logger.isDebugEnabled())
			logger.debug("Sending headers with chunked body to " + remote);
		setHeader(TRANSFER_ENCODING, "chunked");
		byte[] headers = generateHeaders(true).getBytes(StandardCharsets.US_ASCII);
		ISynchronizationPoint<IOException> sendHeaders = remote.send(ByteBuffer.wrap(headers));
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
		sendHeaders.listenInline(() -> {
			if (bodyIn instanceof IO.Readable.Buffered)
				ChunkedTransfer.send(remote, (IO.Readable.Buffered)bodyIn).listenInline(sp);
			else
				ChunkedTransfer.send(remote, bodyIn, 65536, 3).listenInline(sp);
		}, sp);
		return sp;
	}
	
}
