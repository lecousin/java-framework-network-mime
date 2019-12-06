package net.lecousin.framework.network.mime.header;

import java.io.IOException;
import java.util.List;

import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.parser.MimeHeaderValueParser;
import net.lecousin.framework.network.mime.header.parser.Token;

/**
 * Interface for a header format.
 * Header formats are able to parse the value of a Mime header field, so its content can be used.
 * In the other way, a format can generate a raw value.
 */
public interface HeaderValueFormat {

	/** Parse the given raw value. */
	default void parseRawValue(String raw) throws MimeException {
		parseTokens(MimeHeaderValueParser.parse(raw));
	}
	
	/** Parse the given tokens. */
	void parseTokens(List<Token> tokens) throws MimeException;
	
	/** Generate tokens. */
	List<Token> generateTokens();
	
	/** Generate Mime header lines. */
	default void generate(Appendable s, int firstLineMaxLength, int maxSubLineLength) {
		List<Token> tokens = generateTokens();
		int lineLength = 0;
		boolean firstLine = true;
		try {
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
		} catch (IOException e) {
			// should not happen if Appendable is a StringBuilder or IString
		}
	}
	
}
