package gp.tmti;

/**
 * A class representing the different types of results.
 * 
 * @author romanm
 */
public class Result {
	public ResultType type;
	protected Automaton m;

	public static Result automaton(Automaton m) {
		var result = new Result(ResultType.OK, m);
		return result;
	}

	public Automaton get() {
		assert type == ResultType.OK;
		return m;
	}

	protected Result(ResultType type, Automaton m) {
		this.type = type;
		this.m = m;
	}
}