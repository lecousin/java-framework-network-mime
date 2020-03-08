package net.lecousin.framework.network.mime.header.parser;

import java.util.List;

/** Address token. */
public class Address extends SurroundedTokens {

	/** Constructor. */
	public Address(List<Token> tokens) {
		super('<', '>', tokens);
	}
	
}
