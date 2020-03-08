package net.lecousin.framework.network.mime.header.parser;

import java.util.List;

/** Comment token. */
public class Comment extends SurroundedTokens {

	/** Constructor. */
	public Comment(List<Token> tokens) {
		super('(', ')', tokens);
	}
	
}
