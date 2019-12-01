package net.lecousin.framework.network.mime;

import java.io.EOFException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.encoding.Base64;
import net.lecousin.framework.io.encoding.QuotedPrintable;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoder;
import net.lecousin.framework.network.mime.transfer.encoding.ContentDecoderFactory;

/** Utility methods for MIME Messages. */
public final class MimeUtil {
	
	private MimeUtil() { /* no instance */ }

	/** Decode a header content using RFC 2047, which specifies encoded word as follows:
	 * encoded-word = "=?" charset "?" encoding "?" encoded-text "?=".
	 */
	public static String decodeRFC2047(String value) throws IOException {
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
	public static String decodeRFC2047Word(String encodedWord) throws IOException {
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
	
	/** Encode a header parameter value, taking bytes in the given charset,
	 * and depending on its content it may be directly returned,
	 * it may use double-quote or it may use the RFC 2047 encoding. */
	public static String encodeValue(String value, Charset charset) {
		byte[] bytes = value.getBytes(charset);
		boolean hasSpecialChars = false;
		boolean needsQuote = false;
		for (int i = 0; i < bytes.length; ++i) {
			if (bytes[i] == ' ' || bytes[i] == '\t' || bytes[i] == '"')
				needsQuote = true;
			if (bytes[i] < 32 || bytes[i] > 126) {
				hasSpecialChars = true;
				break;
			}
		}
		if (!hasSpecialChars) {
			if (!needsQuote)
				return value;
			return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
		StringBuilder s = new StringBuilder(value.length() + 64);
		s.append("=?UTF-8?B?");
		s.append(new String(Base64.encodeBase64(bytes), StandardCharsets.US_ASCII));
		s.append("?=");
		return s.toString();
	}

	/** Encode a header parameter value, taking bytes in UTF-8,
	 * and depending on its content it may be directly returned,
	 * it may use double-quote or it may use the RFC 2047 encoding. */
	public static String encodeUTF8Value(String value) {
		return encodeValue(value, StandardCharsets.UTF_8);
	}
	
	/** Create a MimeMessage with a Content-Type header and the body from the given string. */
	public static MimeMessage mimeFromString(String content, Charset charset, String contentType) {
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw(MimeMessage.CONTENT_TYPE, contentType + ";charset=" + charset.name());
		mime.setBodyToSend(new ByteArrayIO(content.getBytes(charset), "Mime from string"));
		return mime;
	}
	
	/** Utility class to receive MIME header lines. */
	public static class HeadersLinesReceiver {
		
		/** Constructor with the list of headers to fill. */
		public HeadersLinesReceiver(List<MimeHeader> headers) {
			this.headers = headers;
		}
		
		private List<MimeHeader> headers;
		private String currentName;
		private StringBuilder currentValue;
		
		public List<MimeHeader> getHeaders() {
			return headers;
		}
		
		/** Parse a new line. */
		public void newLine(CharSequence line) throws MimeException {
			if (line.length() == 0) {
				if (currentName != null) {
					headers.add(new MimeHeader(currentName, currentValue.toString()));
					currentName = null;
					currentValue = null;
				}
				return;
			}
			char c = line.charAt(0);
			if (c == ' ' || c == '\t') {
				if (currentName == null)
					throw new MimeException("Invalid Mime header first line: cannot start with a space");
				currentValue.append(line.subSequence(1, line.length()));
				return;
			}
			if (c == ':')
				throw new MimeException("Invalid Mime header: no header name");
			int i = 1;
			int l = line.length();
			while (i < l) {
				if (line.charAt(i) == ':')
					break;
				i++;
			}
			if (i == l)
				throw new MimeException("Invalid Mime header line: <" + line + ">");
			if (currentName != null)
				headers.add(new MimeHeader(currentName, currentValue.toString()));
			currentName = line.subSequence(0, i).toString().trim();
			while (++i < l) {
				c = line.charAt(i);
				if (c != ' ' && c != '\t')
					break;
			}
			if (i >= l)
				currentValue = new StringBuilder(128);
			else
				currentValue = new StringBuilder(line.subSequence(i, l));
		}
	}
	
	/** Parse the input and generate a MimeMessage. */
	public static AsyncSupplier<MimeMessage, IOException> parseMimeMessage(IO.Readable.Buffered input) {
		MessageParser parser = new MessageParser(input);
		return parser.sp;
	}
	
	private static class MessageParser {
		private MessageParser(IO.Readable.Buffered input) {
			io = input;
			mime = new MimeMessage();
			header = new MimeUtil.HeadersLinesReceiver(mime.getHeaders());
			nextBuffer();
		}
		
		private IO.Readable.Buffered io;
		private AsyncSupplier<MimeMessage, IOException> sp = new AsyncSupplier<>();
		private MimeMessage mime;
		private MimeUtil.HeadersLinesReceiver header;
		private StringBuilder headerLine = new StringBuilder(128);
		private ContentDecoder bodyDecoder = null;
		
		public void nextBuffer() {
			io.readNextBufferAsync().onDone(this::parse, sp);
		}
		
		private void parse(ByteBuffer buffer) {
			if (header == null) {
				if (buffer == null) {
					bodyDecoder.endOfData().onDone(() -> ((IOInMemoryOrFile)mime.getBodyReceivedAsInput())
						.seekAsync(SeekType.FROM_BEGINNING, 0).onDone(() -> sp.unblockSuccess(mime), sp), sp);
					return;
				}
				bodyDecoder.decode(buffer).onDone(this::nextBuffer, sp);
				return;
			}
			if (buffer == null) {
				sp.error(new EOFException("Unexpected end of MIME message"));
				return;
			}
			new Task.Cpu<Void, NoException>("Parsing MIME Message", io.getPriority()) {
				@Override
				public Void run() {
					while (buffer.hasRemaining()) {
						byte b = buffer.get();
						if (b == '\n') {
							String line;
							if (headerLine.length() > 0 && headerLine.charAt(headerLine.length() - 1) == '\r')
								line = headerLine.substring(0, headerLine.length() - 1);
							else
								line = headerLine.toString();
							try { header.newLine(line); }
							catch (Exception e) {
								sp.error(IO.error(e));
								return null;
							}
							if (line.length() == 0) {
								// end of MIME headers
								headerLine = null;
								setBody(buffer);
								return null;
							}
							headerLine = new StringBuilder(128);
							continue;
						}
						headerLine.append((char)b);
					}
					nextBuffer();
					return null;
				}
			}.start();
		}
		
		private void setBody(ByteBuffer buffer) {
			IOInMemoryOrFile body = new IOInMemoryOrFile(65536, io.getPriority(), "MIME body from " + io.getSourceDescription());
			bodyDecoder = ContentDecoderFactory.createDecoder(body, mime);
			mime.setBodyReceived(body);
			header = null;
			parse(buffer);
		}
	}
	
}
