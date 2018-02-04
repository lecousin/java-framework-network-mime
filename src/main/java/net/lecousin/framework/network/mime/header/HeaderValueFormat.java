package net.lecousin.framework.network.mime.header;

import java.util.List;

import net.lecousin.framework.network.mime.header.parser.MimeHeaderValueParser;
import net.lecousin.framework.network.mime.header.parser.Token;
import net.lecousin.framework.util.IString;

/**
 * Interface for a header format.
 * Header formats are able to parse the value of a Mime header field, so its content can be used.
 * In the other way, a format can generate a raw value.
 */
public interface HeaderValueFormat {

	/** Parse the given raw value. */
	default void parseRawValue(String raw) throws Exception {
		parseTokens(MimeHeaderValueParser.parse(raw));
	}
	
	/** Parse the given tokens. */
	void parseTokens(List<Token> tokens) throws Exception;
	
	/** Genreate tokens. */
	List<Token> generateTokens();
	
	/** Generate Mime header lines. */
	default void generate(StringBuilder s, int firstLineMaxLength, int maxSubLineLength) {
		List<Token> tokens = generateTokens();
		int lineLength = 0;
		boolean firstLine = true;
		for (Token token : tokens) {
			String ts = token.asText();
			if (firstLine && lineLength + ts.length() > firstLineMaxLength) {
				firstLine = false;
				s.append("\r\n\t");
				lineLength = ts.length();
				s.append(ts);
			} else if (!firstLine && lineLength + ts.length() > maxSubLineLength) {
				s.append("\r\n\t");
				lineLength = ts.length();
				s.append(ts);
			} else {
				lineLength += ts.length();
				s.append(ts);
			}
		}
	}
	
	/** Generate Mime header lines. */
	default void generate(IString s, int firstLineMaxLength, int maxSubLineLength) {
		List<Token> tokens = generateTokens();
		int lineLength = 0;
		boolean firstLine = true;
		for (Token token : tokens) {
			String ts = token.asText();
			if (firstLine && lineLength + ts.length() > firstLineMaxLength) {
				firstLine = false;
				s.append("\r\n\t");
				lineLength = ts.length();
				s.append(ts);
			} else if (!firstLine && lineLength + ts.length() > maxSubLineLength) {
				s.append("\r\n\t");
				lineLength = ts.length();
				s.append(ts);
			} else {
				lineLength += ts.length();
				s.append(ts);
			}
		}
	}
	
}
