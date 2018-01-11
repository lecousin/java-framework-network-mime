package net.lecousin.framework.network.mime;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import net.lecousin.framework.io.encoding.Base64;
import net.lecousin.framework.io.encoding.QuotedPrintable;
import net.lecousin.framework.util.Pair;

//skip checkstyle: AbbreviationAsWordInName
/**
 * Utility methods for MIME, typically encoding and decoding headers.
 */
public final class MIMEUtil {

	/** Parse the given header field, using syntax "value; paramName=paramValue; anotherParam=anotherValue". */
	public static Pair<String, Map<String, String>> parseParameterizedHeader(String headerContent) throws IOException {
		if (headerContent == null) return null;
		int i = headerContent.indexOf(';');
		if (i < 0)
			return new Pair<>(headerContent.trim(), new HashMap<>());
		String firstValue = headerContent.substring(0, i).trim();
		firstValue = decodeHeaderRFC2047(firstValue);
		headerContent = headerContent.substring(i + 1);
		boolean inQuote = false;
		boolean inValue = false;
		Map<String, String> values = new HashMap<>();
		StringBuilder name = new StringBuilder();
		StringBuilder value = new StringBuilder();
		for (i = 0; i < headerContent.length(); ++i) {
			char c = headerContent.charAt(i);
			if (inQuote) {
				if (c == '\\') {
					if (i < headerContent.length() - 1)
						c = headerContent.charAt(++i);
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

	/** Encode a header parameter value, taking bytes in UTF-8,
	 * and depending on its content it may be directly returned,
	 * it may use double-quote or it may use the RFC 2047 encoding. */
	public static String encodeUTF8HeaderParameterValue(String value) {
		return encodeHeaderParameterValue(value, StandardCharsets.UTF_8);
	}
	
}
