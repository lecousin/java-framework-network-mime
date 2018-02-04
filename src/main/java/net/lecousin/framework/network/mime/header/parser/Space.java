package net.lecousin.framework.network.mime.header.parser;

/** Space token. */
public class Space implements Token {

	/** Constructor. */
	public Space() {
	}
	
	@Override
	public String asText() {
		return " ";
	}
	
}
