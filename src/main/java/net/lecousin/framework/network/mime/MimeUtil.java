package net.lecousin.framework.network.mime;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.encoding.EncodingException;
import net.lecousin.framework.encoding.QuotedPrintable;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.text.ByteArrayStringIso8859;
import net.lecousin.framework.text.CharArrayString;
import net.lecousin.framework.text.CharArrayStringBuffer;

/** Utility methods for MIME Messages. */
public final class MimeUtil {
	
	private MimeUtil() {
		/* no instance */
	}
	
	/** Decode a header content using RFC 2047, which specifies encoded word as follows:
	 * encoded-word = "=?" charset "?" encoding "?" encoded-text "?=".
	 */
	public static String decodeRFC2047(String value) throws EncodingException, UnsupportedEncodingException {
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
	public static String decodeRFC2047Word(String encodedWord) throws EncodingException, UnsupportedEncodingException {
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
			byte[] decoded = Base64Encoding.instance.decode(encodedWord.getBytes(StandardCharsets.US_ASCII));
			return new String(decoded, charsetName);
		} else if ("Q".equals(encoding)) {
			QuotedPrintable.Decoder decoder = new QuotedPrintable.Decoder();
			ByteArray.Writable input = new ByteArray.Writable(encodedWord.getBytes(StandardCharsets.US_ASCII), true);
			ByteArray.Writable output = new ByteArray.Writable(ByteArrayCache.getInstance().get(input.remaining() + 1, true), true);
			decoder.decode(input, output, true);
			String decoded = new String(output.getArray(), 0, output.position(), charsetName);
			input.free();
			output.free();
			return decoded;
		} else {
			throw new UnsupportedEncodingException("RFC 2047 encoding " + encoding);
		}
	}
	
	/** Return true if the given byte is a valid token character according
	 * to <a href="https://tools.ietf.org/html/rfc7230#section-3.2.6">RFC 7230</a>.
	 */
	public static boolean isValidTokenCharacter(byte c) {
		if (c < 0x21)
			return false;
		if (c > 0x5D) {
			if (c < 0x7B)
				return true;
			return c == 0x7C || c == 0x7E;
		}
		if (c > 0x40)
			return c < 0x5B;
		if (c > 0x2F)
			return c < 0x3A;
		if (c < 0x28)
			return c != 0x22;
		return c == 0x2A || c == 0x2B || c == 0x2D || c == 0x2E;
	}
	
	/** Encode a string into a token, which may need double quote according to
	 * <a href="https://tools.ietf.org/html/rfc7230#section-3.2.6">RFC 7230</a>.
	 */
	public static String encodeToken(String value) {
		for (int i = value.length() - 1; i >= 0; i--)
			if (!isValidTokenCharacter((byte)value.charAt(i))) {
				return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
			}
		return value;
	}
	
	/** Encode a header parameter value, taking bytes in the given charset,
	 * and depending on its content it may be directly returned,
	 * it may use double-quote if needed
	 * or it may use the RFC 2047 encoding. */
	public static String encodeHeaderValue(String value, Charset charset) {
		byte[] bytes = value.getBytes(charset);
		boolean hasSpecialChars = false;
		boolean needsQuote = false;
		for (int i = 0; i < bytes.length; ++i) {
			if (!needsQuote && 
				(bytes[i] == ' ' || bytes[i] == '\t' || bytes[i] == '"' || bytes[i] == '='))
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
		CharArrayStringBuffer s = new CharArrayStringBuffer(new CharArrayString(value.length() + 64));
		s.append("=?");
		s.append(charset.name());
		s.append("?B?");
		s.append(new ByteArrayStringIso8859(Base64Encoding.instance.encode(bytes)));
		s.append("?=");
		return s.toString();
	}

	/** Encode a header parameter value, taking bytes in UTF-8,
	 * and depending on its content it may be directly returned,
	 * it may use double-quote or it may use the RFC 2047 encoding. */
	public static String encodeHeaderValueWithUTF8(String value) {
		return encodeHeaderValue(value, StandardCharsets.UTF_8);
	}
	
}
