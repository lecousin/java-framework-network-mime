package net.lecousin.framework.network.mime.header.parser;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.text.CharArrayString;
import net.lecousin.framework.text.IString;

/** RFC 822 Header field token. */
public interface Token {
	
	/** Calculate the length of this token if converted into a string. */
	int textLength();
	
	/** Calculate the length of the given tokens if converted into a string. */
	static int textLength(List<Token> tokens) {
		int total = 0;
		for (Token token : tokens)
			total += token.textLength();
		return total;
	}
	
	/** Convert this token into corresponding string. */
	void asText(IString s);
	
	/** Convert the given tokens into corresponding string. */
	static void asText(List<Token> tokens, IString s) {
		for (Token token : tokens)
			token.asText(s);
	}

	/** Convert this token into corresponding string. */
	default String asString() {
		CharArrayString s = new CharArrayString(textLength());
		asText(s);
		return s.toString();
	}

	/** Convert the given tokens into corresponding string. */
	static String toString(List<Token> tokens) {
		CharArrayString s = new CharArrayString(textLength(tokens));
		asText(tokens, s);
		return s.asString();
	}

	/** Remove any leading or trailing space tokens. */
	static void trim(List<Token> tokens) {
		while (!tokens.isEmpty() && (tokens.get(0) instanceof Space))
			tokens.remove(0);
		while (!tokens.isEmpty() && (tokens.get(tokens.size() - 1) instanceof Space))
			tokens.remove(tokens.size() - 1);
	}
	
	/** Remove comment tokens. */
	@SuppressWarnings("squid:ForLoopCounterChangedCheck")
	static void removeComments(List<Token> tokens) {
		for (Iterator<Token> it = tokens.iterator(); it.hasNext(); )
			if (it.next() instanceof Comment)
				it.remove();
	}
	
	/** Split into lists of tokens, using the given special character. */
	static List<List<Token>> splitBySpecialCharacter(List<Token> tokens, char sc) {
		List<List<Token>> list = new LinkedList<>();
		List<Token> current = new LinkedList<>();
		for (Token token : tokens) {
			if ((token instanceof SpecialCharacter) && ((SpecialCharacter)token).getChar() == sc) {
				if (!current.isEmpty())
					list.add(current);
				current = new LinkedList<>();
			} else {
				current.add(token);
			}
		}
		if (!current.isEmpty())
			list.add(current);
		return list;
	}
	
}
