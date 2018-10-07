package net.lecousin.framework.network.mime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.collections.LinkedArrayList;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.AsyncWork.AsyncWorkListenerReady;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.LinkedIO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.mime.header.HeaderValueFormat;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValues;
import net.lecousin.framework.network.mime.transfer.ChunkedTransfer;
import net.lecousin.framework.network.mime.transfer.IdentityTransfer;
import net.lecousin.framework.util.IString;
import net.lecousin.framework.util.UnprotectedString;
import net.lecousin.framework.util.UnprotectedStringBuffer;

/** MIME Message (RFC 822). */
public class MimeMessage {
	
	public static final char[] CRLF = new char[] { '\r', '\n' };

	/** Constructor. */
	public MimeMessage() {
	}
	
	/** Constructor. */
	public MimeMessage(List<MimeHeader> headers) {
		this.headers.addAll(headers);
	}

	/** Constructor. */
	public MimeMessage(MimeHeader... headers) {
		for (MimeHeader h : headers)
			this.headers.add(h);
	}
	
	private Logger logger = LCCore.getApplication().getLoggerFactory().getLogger(MimeMessage.class);
	
	public Logger getLogger() {
		return logger;
	}
	
	// ***** Headers *****
	
	private LinkedArrayList<MimeHeader> headers = new LinkedArrayList<>(10);
	
	public List<MimeHeader> getHeaders() {
		return headers;
	}
	
	/** Return the list of headers with the given name (case insensitive). */
	public List<MimeHeader> getHeaders(String name) {
		name = name.toLowerCase();
		ArrayList<MimeHeader> list = new ArrayList<>();
		for (MimeHeader h : headers)
			if (h.getNameLowerCase().equals(name))
				list.add(h);
		return list;
	}
	
	/** Return the list of headers values with the given name (case insensitive), parsed into the requested format. */
	public <T extends HeaderValueFormat> List<T> getHeadersValues(String name, Class<T> format) throws Exception {
		List<T> list = new LinkedList<>();
		name = name.toLowerCase();
		for (MimeHeader h : headers)
			if (h.getNameLowerCase().equals(name))
				list.add(h.getValue(format));
		return list;
	}
	
	/** Return the first header with the given name (case insensitive) or null. */
	public MimeHeader getFirstHeader(String name) {
		name = name.toLowerCase();
		for (MimeHeader h : headers)
			if (h.getNameLowerCase().equals(name))
				return h;
		return null;
	}
	
	/** Return the value of the first header with the given name (case insensitive) parsed into the requested format, or null. */
	public <T extends HeaderValueFormat> T getFirstHeaderValue(String name, Class<T> format) throws Exception {
		MimeHeader h = getFirstHeader(name);
		if (h == null)
			return null;
		return h.getValue(format);
	}
	
	/** Return the value of the first header with the given name (case insensitive), or null. */
	public String getFirstHeaderRawValue(String name) {
		MimeHeader h = getFirstHeader(name);
		if (h == null)
			return null;
		return h.getRawValue();
	}
	
	/** Return the value of the first header with the given name (case insensitive) parsed into a Long, or null. */
	public Long getFirstHeaderLongValue(String name) {
		MimeHeader h = getFirstHeader(name);
		if (h == null)
			return null;
		try {
			return Long.valueOf(h.getRawValue());
		} catch (Exception e) {
			return null;
		}
	}
	
	/** Return true if thie message contains at least one header with the given name (case insensitive). */
	public boolean hasHeader(String name) {
		return getFirstHeader(name) != null;
	}
	
	/** Append a header. */
	public void addHeaderRaw(String name, String rawValue) {
		headers.add(new MimeHeader(name, rawValue));
	}
	
	/** Append a header. */
	public void addHeader(String name, HeaderValueFormat value) {
		headers.add(new MimeHeader(name, value));
	}
	
	/** Append a header. */
	public void addHeader(MimeHeader header) {
		headers.add(header);
	}

	/** Remove any header with the same name, and append this new header. */
	public void setHeaderRaw(String name, String rawValue) {
		removeHeaders(name);
		addHeaderRaw(name, rawValue);
	}
	
	/** Remove any header with the same name, and append this new header. */
	public void setHeader(String name, HeaderValueFormat value) {
		removeHeaders(name);
		addHeader(name, value);
	}
	
	/** Remove any header with the same name, and append this new header. */
	public void setHeader(MimeHeader header) {
		removeHeaders(header.getName());
		addHeader(header);
	}
	
	/** Remove any header with the given name. */
	public void removeHeaders(String name) {
		name = name.toLowerCase();
		for (Iterator<MimeHeader> it = headers.iterator(); it.hasNext(); )
			if (it.next().getNameLowerCase().equals(name))
				it.remove();
	}
	
	/** Generate headers into the given string. */
	public void appendHeadersTo(StringBuilder s) {
		for (MimeHeader h : headers)
			h.appendTo(s);
	}
	
	/** Generate headers into the given string. */
	public void appendHeadersTo(IString s) {
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
	public static final String CONNECTION = "Connection";
	
	public Long getContentLength() {
		return getFirstHeaderLongValue(CONTENT_LENGTH);
	}
	
	/** Set the Content-Length header. */
	public void setContentLength(long size) {
		setHeaderRaw(CONTENT_LENGTH, Long.toString(size));
	}

	/** Parse the Content-Type header and return it, or null if it is not present. */
	public ParameterizedHeaderValue getContentType() throws Exception {
		return getFirstHeaderValue(CONTENT_TYPE, ParameterizedHeaderValue.class);
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
	
	
	// ***** Receive Body *****

	private IO.Writable bodyReceived = null;
	
	/** Set the IO that receive the body and can be read later on. */
	public <T extends IO.Writable & IO.Readable> void setBodyReceived(T output) {
		bodyReceived = output;
	}
	
	/** Return the body previously set by {@link #setBodyReceived(net.lecousin.framework.io.IO.Writable)}. */
	public IO.Writable getBodyReceivedAsOutput() {
		return bodyReceived;
	}
	
	/** Return the body previously set by {@link #setBodyReceived(net.lecousin.framework.io.IO.Writable)}. */
	public IO.Readable getBodyReceivedAsInput() {
		return (IO.Readable)bodyReceived;
	}

	
	// ***** Body To Send *****
	
	private IO.Readable bodyToSend = null;
	
	/** Return the body to send, previoulsy set by setBodyToSend. */
	public IO.Readable getBodyToSend() {
		return bodyToSend;
	}
	
	/** Set the body to send. */
	public void setBodyToSend(IO.Readable body) {
		this.bodyToSend = body;
	}
	
	/** Generate this MimeMessage into a Readable IO. */
	@SuppressWarnings("resource")
	public IO.Readable getReadableStream() {
		UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
		appendHeadersTo(s);
		s.append(CRLF);
		ByteArrayIO headers = new ByteArrayIO(s.toUsAsciiBytes(), "Mime Headers");
		IO.Readable body = getBodyToSend();
		if (body == null)
			return headers;
		if (body instanceof IO.Readable.Buffered) {
			if (body instanceof IO.KnownSize)
				return new LinkedIO.Readable.Buffered.DeterminedSize("Mime Message", headers, (IO.Readable.Buffered)body);
			return new LinkedIO.Readable.Buffered("Mime Message", headers, (IO.Readable.Buffered)body);
		}
		if (body instanceof IO.KnownSize)
			return new LinkedIO.Readable.DeterminedSize("Mime Message", headers, body);
		return new LinkedIO.Readable("Mime Message", headers, body);
	}
	
	// --- Receive ---
	
	/** Receive header lines from the given client. */
	public SynchronizationPoint<IOException> readHeader(TCPClient client, int timeout) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		if (logger.debug())
			logger.debug("Receiving header lines...");
		MimeUtil.HeadersLinesReceiver linesReceiver = new MimeUtil.HeadersLinesReceiver(headers);
		AsyncWork<ByteArrayIO,IOException> readLine = client.getReceiver().readUntil((byte)'\n', 1024, timeout);
		readLine.listenInline(new AsyncWorkListenerReady<ByteArrayIO, IOException>((line, that) -> {
			String s = line.getAsString(StandardCharsets.US_ASCII);
			if (logger.debug())
				logger.debug("Header line received: " + s);
			int i = s.indexOf('\r');
			if (i >= 0) s = s.substring(0,i);
			i = s.indexOf('\n');
			if (i >= 0) s = s.substring(0,i);
			try { linesReceiver.newLine(s); }
			catch (Exception e) {
				result.error(IO.error(e));
				return;
			}
			if (s.length() == 0) {
				if (logger.debug())
					logger.debug("End of header lines");
				result.unblock();
				return;
			}
			client.getReceiver().readUntil((byte)'\n', 1024, timeout).listenInline(that);
		}, result));
		return result;
	}
	
	// --- Send ---
	
	/** Send this MIME to the given TCP connection. */
	@SuppressWarnings("resource")
	public ISynchronizationPoint<IOException> send(TCPRemote remote) {
		IO.Readable body = getBodyToSend();
		
		if (body == null) {
			if (logger.debug())
				logger.debug("Sending headers without body to " + remote);
			setContentLength(0);
			UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
			appendHeadersTo(s);
			s.append(CRLF);
			byte[] headers = s.toUsAsciiBytes();
			return remote.send(ByteBuffer.wrap(headers));
		}
		
		ParameterizedHeaderValues transferEncoding;
		try { transferEncoding = getFirstHeaderValue(TRANSFER_ENCODING, ParameterizedHeaderValues.class); }
		catch (Exception e) { transferEncoding = null; }
		if ((body instanceof IO.KnownSize) && (transferEncoding == null || !transferEncoding.hasMainValue("chunked"))) {
			SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
			((IO.KnownSize)body).getSizeAsync().listenInline((size) -> {
				new Task.Cpu.FromRunnable("Send MIME to " + remote, body.getPriority(), () -> {
					if (logger.debug())
						logger.debug("Sending headers with body of " + size.longValue() + " to " + remote);
					setContentLength(size.longValue());
					UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
					appendHeadersTo(s);
					s.append(CRLF);
					byte[] headers = s.toUsAsciiBytes();
					ISynchronizationPoint<IOException> sendHeaders = remote.send(ByteBuffer.wrap(headers));
					sendHeaders.listenInline(() -> {
						if (body instanceof IO.Readable.Buffered)
							IdentityTransfer.send(remote, (IO.Readable.Buffered)body).listenInline(sp);
						else
							IdentityTransfer.send(remote, body, 65536, 3).listenInline(sp);
					}, sp);
				}).start();
			}, sp);
			return sp;
		}
		if (logger.debug())
			logger.debug("Sending headers with chunked body to " + remote);
		setHeaderRaw(TRANSFER_ENCODING, "chunked");
		removeHeaders(CONTENT_LENGTH);
		UnprotectedStringBuffer s = new UnprotectedStringBuffer(new UnprotectedString(512));
		appendHeadersTo(s);
		s.append(CRLF);
		byte[] headers = s.toUsAsciiBytes();
		ISynchronizationPoint<IOException> sendHeaders = remote.send(ByteBuffer.wrap(headers));
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
		sendHeaders.listenInline(() -> {
			if (body instanceof IO.Readable.Buffered)
				ChunkedTransfer.send(remote, (IO.Readable.Buffered)body).listenInline(sp);
			else
				ChunkedTransfer.send(remote, body, 65536, 3).listenInline(sp);
		}, sp);
		return sp;
	}
}
