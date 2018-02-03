package net.lecousin.framework.network.mime.header.parser;

import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.util.Pair;

public final class MimeHeaderValueParser {

	private MimeHeaderValueParser() { /* no instance */ }
	
	public static List<Token> parse(String value) {
		Pair<List<Token>, Integer> p = parse(value, 0, (char)0);
		return p.getValue1();
	}

	private static Pair<List<Token>, Integer> parse(String value, int i, char end) {
		int l = value.length();
		LinkedList<Token> tokens = new LinkedList<>();
		boolean escape = false;
		StringBuilder currentWord = null;
		for (; i < l; ++i) {
			char c = value.charAt(i);
			if (c == end)
				return new Pair<>(tokens, Integer.valueOf(i));
			if (escape) {
				if (currentWord == null)
					currentWord = new StringBuilder();
				currentWord.append(c);
				escape = false;
				continue;
			}
			if (c == '"') {
				if (currentWord == null)
					currentWord = new StringBuilder();
				for (int j = i + 1; j < l; ++j) {
					c = value.charAt(j);
					if (escape) {
						currentWord.append(c);
						escape = false;
						continue;
					}
					if (c == '\\') {
						escape = true;
						continue;
					}
					if (c == '"') {
						i = j;
						break;
					}
					currentWord.append(c);
				}
				continue;
			}
			if (c == '\\') {
				escape = true;
				continue;
			}
			if (c == ' ' || c == '\t') {
				if (currentWord != null) {
					tokens.add(new Word(currentWord.toString()));
					currentWord = null;
				}
				if (!tokens.isEmpty() && tokens.getLast() instanceof Space)
					continue;
				tokens.add(new Space());
				continue;
			}
			if (c == '(') {
				if (currentWord != null) {
					tokens.add(new Word(currentWord.toString()));
					currentWord = null;
				}
				Pair<List<Token>, Integer> p = parse(value, i + 1, ')');
				tokens.add(new Comment(p.getValue1()));
				i = p.getValue2().intValue();
				continue;
			}
			if (c == '[') {
				if (currentWord != null) {
					tokens.add(new Word(currentWord.toString()));
					currentWord = null;
				}
				Pair<List<Token>, Integer> p = parse(value, i + 1, ']');
				tokens.add(new DomainLiteral(p.getValue1()));
				i = p.getValue2().intValue();
				continue;
			}
			if (c == '<') {
				if (currentWord != null) {
					tokens.add(new Word(currentWord.toString()));
					currentWord = null;
				}
				Pair<List<Token>, Integer> p = parse(value, i + 1, ']');
				tokens.add(new Address(p.getValue1()));
				i = p.getValue2().intValue();
				continue;
			}
			if (c == '@' || c == ',' || c == ';' || c == ':' || c == '.') {
				if (currentWord != null) {
					tokens.add(new Word(currentWord.toString()));
					currentWord = null;
				}
				tokens.add(new SpecialCharacter(c));
				continue;
			}
			if (currentWord == null)
				currentWord = new StringBuilder();
			currentWord.append(c);
		}
		if (currentWord != null) {
			tokens.add(new Word(currentWord.toString()));
			currentWord = null;
		}
		return new Pair<>(tokens, Integer.valueOf(i));
	}
	
}
