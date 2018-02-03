package net.lecousin.framework.network.mime.header.parser;

import java.util.LinkedList;
import java.util.List;

public interface Token {
	
	public String asText();
	
	public static String asText(List<Token> tokens) {
		StringBuilder s = new StringBuilder();
		for (Token token : tokens)
			s.append(token.asText());
		return s.toString();
	}

	public static void trim(List<Token> tokens) {
		while (!tokens.isEmpty() && (tokens.get(0) instanceof Space))
			tokens.remove(0);
		while (!tokens.isEmpty() && (tokens.get(tokens.size() - 1) instanceof Space))
			tokens.remove(tokens.size() - 1);
	}
	
	public static void removeComments(List<Token> tokens) {
		for (int i = 0; i < tokens.size(); )
			if (tokens.get(i) instanceof Comment)
				tokens.remove(i);
			else
				i++;
	}
	
	public static List<List<Token>> splitBySpecialCharacter(List<Token> tokens, char sc) {
		List<List<Token>> list = new LinkedList<>();
		List<Token> current = new LinkedList<>();
		for (Token token : tokens) {
			if ((token instanceof SpecialCharacter) && ((SpecialCharacter)token).getChar() == sc) {
				if (!current.isEmpty())
					list.add(current);
				current = new LinkedList<>();
			} else
				current.add(token);
		}
		if (!current.isEmpty())
			list.add(current);
		return list;
	}
	
}
