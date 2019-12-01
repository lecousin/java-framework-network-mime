package net.lecousin.framework.network.mime.header.parser;

import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.util.Pair;

/** Parser. */
public final class MimeHeaderValueParser {

	private MimeHeaderValueParser() { /* no instance */ }
	
	/** Parse. */
	public static List<Token> parse(String value) {
		Pair<List<Token>, Integer> p = parse(value, 0, (char)0);
		return p.getValue1();
	}

	private static Pair<List<Token>, Integer> parse(String value, int i, char end) {
		int l = value.length();
		LinkedList<Token> tokens = new LinkedList<>();
		boolean escape = false;
		StringBuilder currentWord = null;
		Pair<List<Token>, Integer> p;
		for (; i < l; ++i) {
			char c = value.charAt(i);
			if (c == end) {
				appendWord(tokens, currentWord);
				return new Pair<>(tokens, Integer.valueOf(i));
			}
			if (escape) {
				if (currentWord == null)
					currentWord = new StringBuilder();
				currentWord.append(c);
				escape = false;
				continue;
			}
			switch (c) {
			case '"':
				if (currentWord == null)
					currentWord = new StringBuilder();
				i = eatString(currentWord, value, i, l);
				break;
			case '\\':
				escape = true;
				break;
			case ' ':
			case '\t':
				currentWord = appendWord(tokens, currentWord);
				if (!tokens.isEmpty() && tokens.getLast() instanceof Space)
					continue;
				tokens.add(new Space());
				break;
			case '(':
				currentWord = appendWord(tokens, currentWord);
				p = parse(value, i + 1, ')');
				tokens.add(new Comment(p.getValue1()));
				i = p.getValue2().intValue();
				break;
			case '[':
				currentWord = appendWord(tokens, currentWord);
				p = parse(value, i + 1, ']');
				tokens.add(new DomainLiteral(p.getValue1()));
				i = p.getValue2().intValue();
				break;
			case '<':
				currentWord = appendWord(tokens, currentWord);
				p = parse(value, i + 1, '>');
				tokens.add(new Address(p.getValue1()));
				i = p.getValue2().intValue();
				break;
			case '@':
			case ',':
			case ';':
			case ':':
			case '.':
				currentWord = appendWord(tokens, currentWord);
				tokens.add(new SpecialCharacter(c));
				break;
			default:
				if (currentWord == null)
					currentWord = new StringBuilder();
				currentWord.append(c);
			}
		}
		if (currentWord != null)
			tokens.add(new Word(currentWord.toString()));
		return new Pair<>(tokens, Integer.valueOf(i));
	}
	
	private static StringBuilder appendWord(LinkedList<Token> tokens, StringBuilder currentWord) {
		if (currentWord != null)
			tokens.add(new Word(currentWord.toString()));
		return null;
	}
	
	private static int eatString(StringBuilder currentWord, String value, int i, int l) {
		boolean escape = false;
		for (int j = i + 1; j < l; ++j) {
			char c = value.charAt(j);
			if (escape) {
				currentWord.append(c);
				escape = false;
				continue;
			}
			if (c == '\\') {
				escape = true;
				continue;
			}
			if (c == '"')
				return j;
			currentWord.append(c);
		}
		return l;
	}
	
}
