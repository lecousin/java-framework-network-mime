package net.lecousin.framework.network.mime;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.encoding.Base64;
import net.lecousin.framework.io.encoding.QuotedPrintable;

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
		s.append(new String(Base64.encodeBase64(bytes)));
		s.append("?=");
		return s.toString();
	}

	/** Encode a header parameter value, taking bytes in UTF-8,
	 * and depending on its content it may be directly returned,
	 * it may use double-quote or it may use the RFC 2047 encoding. */
	public static String encodeUTF8Value(String value) {
		return encodeValue(value, StandardCharsets.UTF_8);
	}
	
	@SuppressWarnings("resource")
	public static MimeMessage mimeFromString(String content, Charset charset, String contentType) {
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw(MimeMessage.CONTENT_TYPE, contentType + ";charset=" + charset.name());
		mime.setBodyToSend(new ByteArrayIO(content.getBytes(charset), "Mime from string"));
		return mime;
	}
	
	public static class HeadersLinesReceiver {
		
		public HeadersLinesReceiver(List<MimeHeader> headers) {
			this.headers = headers;
		}
		
		private List<MimeHeader> headers;
		private String currentName;
		private StringBuilder currentValue;
		
		public List<MimeHeader> getHeaders() {
			return headers;
		}
		
		public void newLine(CharSequence line) throws Exception {
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
					throw new Exception("Invalid Mime header first line: cannot start with a space");
				currentValue.append(line.subSequence(1, line.length()));
				return;
			}
			if (c == ':')
				throw new Exception("Invalid Mime header: no header name");
			int i = 1;
			int l = line.length();
			while (i < l) {
				if (line.charAt(i) == ':')
					break;
				i++;
			}
			if (i == l)
				throw new Exception("Invalid Mime header line: <" + line + ">");
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
	
}
