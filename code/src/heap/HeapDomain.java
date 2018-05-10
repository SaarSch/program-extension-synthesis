package heap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.stringtemplate.v4.ST;

import bgu.cs.util.STGLoader;
import bgu.cs.util.STHierarchyRenderer;
import bgu.cs.util.rel.HashRel2;
import bgu.cs.util.rel.Rel2;
import gp.Domain;
import gp.Plan;
import grammar.CostSize;
import heap.Store.ErrorStore;
import heap.Var.VarRole;

/**
 * A domain for heap-manipulating programs.
 * 
 * @author romanm
 */
public class HeapDomain implements Domain<Store, Stmt, BoolExpr> {
	public final Set<Field> fields = new LinkedHashSet<>();

	/**
	 * The set of all variables (of all types).
	 */
	public final Collection<Var> vars;

	/**
	 * The subset of reference-typed variables.
	 */
	public final List<RefVar> refVars = new ArrayList<>();
	public final List<RefVar> refTemps = new ArrayList<>();
	public final List<RefVar> refArgs = new ArrayList<>();

	public final Collection<RefType> refTypes;
	public final Collection<Type> types = new LinkedHashSet<>();

	public final Rel2<Type, Var> typeToVar = new HashRel2<>();

	protected Collection<Stmt> stmts = new ArrayList<>();

	protected STGLoader templates = new STGLoader(HeapDomain.class);
	protected STHierarchyRenderer renderer = new STHierarchyRenderer(templates);

	@Override
	public String name() {
		return "HeapDomain";
	}

	@Override
	public Guard getTrue() {
		return True.v;
	}

	@Override
	public boolean test(BoolExpr c, Value val) {
		Store state = (Store) val;
		Boolean result = PWhileInterpreter.v.test(c, state);
		return result != null && result.booleanValue();
	}

	@Override
	public boolean match(Store first, Store second) {
		for (Map.Entry<Var, Val> entry : second.getEnvMap().entrySet()) {
			Var var = entry.getKey();
			Val val = entry.getValue();
			if (!first.isInitialized(var) || !first.eval(var).equals(val)) {
				return false;
			}
		}

		for (Obj obj : second.getObjects()) {
			for (Map.Entry<Field, Val> entry : second.geFields(obj).entrySet()) {
				Field field = entry.getKey();
				Val val = entry.getValue();
				if (!first.isInitialized(obj, field) || !first.eval(obj, field).equals(val)) {
					return false;
				}
			}
		}

		// TODO: handle free objects.
		return true;
	}

	@Override
	public Optional<Store> apply(Stmt stmt, Store store) {
		Optional<Store> result = Optional.empty();
		Collection<Store> succs = BasicHeapTR.applier.apply(store, stmt);
		if (succs.size() == 1) {
			Store next = succs.iterator().next();
			if (!(next instanceof ErrorStore)) {
				result = Optional.of(next);
			}
		}
		return result;
	}

	public static HeapDomain fromVarsAndTypes(Collection<Var> vars, Collection<RefType> refTypes) {
		HeapDomain result = new HeapDomain(vars, refTypes);
		return result;
	}

	protected HeapDomain(Collection<Var> vars, Collection<RefType> refTypes) {
		this.vars = vars;
		this.refTypes = refTypes;
		for (RefType type : refTypes) {
			fields.addAll(type.fields);
		}
		types.addAll(refTypes);
		types.add(IntType.v);

		for (Var v : vars) {
			typeToVar.add(v.getType(), v);
			if (v instanceof RefVar) {
				RefVar refVar = (RefVar) v;
				refVars.add(refVar);
				if (refVar.role == VarRole.ARG) {
					refArgs.add(refVar);
				}
				if (refVar.role == VarRole.TEMP) {
					refTemps.add(refVar);
				}
			}
		}

		stmts = generateActions(vars, fields);
	}

	// TODO: make this call explicit and add a flag for allocation statements.
	public Collection<Stmt> generateActions(Collection<Var> vars, Collection<Field> fields) {
		Collection<Stmt> result = new ArrayList<>();

		// Generate variable-to-variable assignments.
		for (Var lhs : vars) {
			if (lhs.readonly) {
				continue;
			}
			for (Var rhs : typeToVar.select1(lhs.getType())) {
				if (lhs != rhs) {
					var stmt = new AssignStmt(new VarExpr(lhs), new VarExpr(rhs));
					result.add(stmt);
				}
			}
		}

		for (RefVar lhs : refVars) {
			if (!lhs.readonly) {
				// lhs = new T()
				result.add(new AssignStmt(new VarExpr(lhs), new NewExpr(lhs.getType())));
				// lhs = null
				result.add(new AssignStmt(new VarExpr(lhs), NullExpr.v));
			}

			for (Field f : lhs.getType().fields) {
				if (f instanceof RefField) {
					// lhs.f = null
					result.add(new AssignStmt(new DerefExpr(new VarExpr(lhs), (RefField) f), NullExpr.v));
				}
				for (Var rhs : typeToVar.select1(f.dstType)) {
					// lhs.f = rhs
					result.add(new AssignStmt(new DerefExpr(new VarExpr(lhs), f), new VarExpr(rhs)));
				}
			}

			if (!lhs.readonly) {
				for (Field f : fields) {
					if (f.dstType == lhs.getType()) {
						for (Var rhs : typeToVar.select1(f.srcType)) {
							// lhs = rhs.f
							result.add(new AssignStmt(new VarExpr(lhs), new DerefExpr(new VarExpr(rhs), f)));
						}
					}
				}
			}
		}

		return result;
	}

	/**
	 * TODO: refine the sorting of the guards by accounting for (as feww as
	 * possible) constants.
	 */
	@Override
	public List<BoolExpr> generateGuards(ArrayList<Plan<Store, Stmt>> plans) {
		var posLiterals = generateBasicGuards(plans);
		var negLiterals = new ArrayList<BoolExpr>();
		for (var e : posLiterals) {
			negLiterals.add(new NotExpr(e));
		}

		final var result = new ArrayList<BoolExpr>();
		result.addAll(posLiterals);
		result.addAll(negLiterals);
		final var doubleCubes = genPairCubes(posLiterals);
		result.addAll(doubleCubes);
		// final var doubleOr = getOr2(posLiterals, doubleCubes);
		final var doubleOrPosPos = genOr2(posLiterals, posLiterals);
		result.addAll(doubleOrPosPos);
		final var doubleOrPosNeg = genOr2(posLiterals, negLiterals);
		result.addAll(doubleOrPosNeg);

		var sizeFun = new CostSize();
		Collections.sort(result, (e1, e2) -> {
			var diff = sizeFun.apply(e1) - sizeFun.apply(e2);
			return (int) diff;
		});
		return result;
	}

	/**
	 * Generates disjunctive expressions from the Cartesian product of the
	 * expressions in the first list and the expressions in the second list.
	 */
	public List<BoolExpr> genOr2(List<BoolExpr> exprs1, List<BoolExpr> exprs2) {
		final var result = new ArrayList<BoolExpr>();
		for (var e1 : exprs1) {
			for (var e2 : exprs2) {
				result.add(new OrExpr(e1, e2));
			}
		}
		return result;
	}

	/**
	 * Generates all cubes of size 2, from the given expressions and their
	 * negations.
	 */
	public List<BoolExpr> genPairCubes(List<BoolExpr> exprs) {
		final var result = new ArrayList<BoolExpr>();
		for (var e1 : exprs) {
			for (var e2 : exprs) {
				if (e1 == e2) {
					continue;
				}
				result.add(new AndExpr(e1, e2));
				result.add(new AndExpr(e1, new NotExpr(e2)));
				result.add(new AndExpr(new NotExpr(e1), e2));
				result.add(new AndExpr(new NotExpr(e1), new NotExpr(e2)));
			}
		}
		return result;
	}

	protected void addBasicIntGuards(ArrayList<Plan<Store, Stmt>> plans, List<BoolExpr> result) {
		// Collect all of the integers constants into a single set.
		final var intVals = new HashSet<IntVal>();
		for (final var plan : plans) {
			for (final Store store : plan.states()) {
				for (final Val v : store.env.values()) {
					if (v instanceof IntVal) {
						intVals.add((IntVal) v);
					}
				}
				for (final Obj o : store.objects) {
					for (final Field field : o.type.fields) {
						if (field.dstType == IntType.v) {
							Val v = store.eval(o, field);
							if (v != null) {
								IntVal iv = (IntVal) v;
								intVals.add(iv);
							}
						}
					}
				}
			}
		}

		// No use generating guards, since they will not be able to separate
		// stores that have no integer values.
		if (intVals.isEmpty()) {
			return;
		}
		
		// Leave only the maximal and minimal numbers.
		var min = intVals.iterator().next();
		var max = intVals.iterator().next();
		for (var val: intVals) {
			if (val.num < min.num) {
				min = val;
			}
			if (val.num > max.num) {
				max = val;
			}
		}
		intVals.clear();
		intVals.add(min);
		intVals.add(max);
		intVals.add(new IntVal(0));
		intVals.add(new IntVal(1));

		final var intExprs = new ArrayList<Expr>();
		// Add variables and variable-field-dereference expressions as basic
		// expressions.
		for (final var domVar : vars) {
			if (domVar instanceof IntVar) {
				intExprs.add(new VarExpr(domVar));
			} else {
				assert domVar instanceof RefVar;
				RefVar refVar = (RefVar) domVar;
				RefType refType = refVar.getType();
				for (var field : refType.fields) {
					if (field instanceof IntField) {
						intExprs.add(new DerefExpr(new VarExpr(domVar), field));
					}
				}
			}
		}

		// Add less-than and equality guards from the integer-valued expressions.
		for (int i = 0; i < intExprs.size(); ++i) {
			var e1 = intExprs.get(i);
			for (int j = 0; j < intExprs.size(); ++j) {
				if (i == j) {
					continue;
				}
				var e2 = intExprs.get(j);
				final var lt1 = new LtExpr(e1, e2);
				result.add(lt1);
				final var lt2 = new LtExpr(e2, e1);
				result.add(lt2);
				// Since equality is symmetric, prune out useless
				// guards.
				if (i < j) {
					final var eq = new EqExpr(e1, e2);
					result.add(eq);
				}
			}
			for (final var iv : intVals) {
				final var lt1 = new LtExpr(e1, new ValExpr(iv));
				result.add(lt1);
				final var lt2 = new LtExpr(new ValExpr(iv), e1);
				result.add(lt2);
				final var eq = new EqExpr(e1, new ValExpr(iv));
				result.add(eq);
			}
		}
	}

	/**
	 * TODO: prune out incorrectly-typed expressions.
	 */
	protected void addBasicRefGuards(ArrayList<Plan<Store, Stmt>> plans, List<BoolExpr> result) {
		boolean storesWithObjects = false;
		for (var plan : plans) {
			for (var store : plan.states()) {
				if (!store.getObjects().isEmpty()) {
					storesWithObjects = true;
					break;
				}
			}
			if (storesWithObjects) {
				break;
			}
		}

		// No use generating reference-related guards, since they will not be able to
		// separate stores without objects.
		if (!storesWithObjects) {
			return;
		}

		final var refExprs = new ArrayList<Expr>();
		// Add variables and variable-field-dereference expressions as basic
		// expressions.
		for (final var domVar : vars) {
			if (domVar instanceof RefVar) {
				assert domVar instanceof RefVar;
				var varExpr = new VarExpr(domVar);
				refExprs.add(varExpr);

				RefVar refVar = (RefVar) domVar;
				RefType refType = refVar.getType();
				for (var field : refType.fields) {
					if (field instanceof RefField) {
						assert field instanceof RefField;
						refExprs.add(new DerefExpr(varExpr, field));
					}
				}
			}
		}
		refExprs.add(NullExpr.v);

		// Add equality guards for reference-valued expressions.
		for (int i = 0; i < refExprs.size(); ++i) {
			final var e1 = refExprs.get(i);
			for (int j = i + 1; j < refExprs.size(); ++j) {
				final var e2 = refExprs.get(j);
				final var eq = new EqExpr(e1, e2);
				result.add(eq);
			}
		}
	}

	@Override
	public List<BoolExpr> generateBasicGuards(ArrayList<Plan<Store, Stmt>> plans) {
		final var result = new ArrayList<BoolExpr>();
		addBasicIntGuards(plans, result);
		addBasicRefGuards(plans, result);
		return result;
	}

	/**
	 * TODO: make the auto-renderer work.
	 */
	@Override
	public String toString() {
		ST template = templates.load("HeapDomain");
		for (Type type : types) {
			if (type instanceof RefType) {
				ST refTypeTemplate = templates.load("RefType");
				refTypeTemplate.add("name", type.name);
				RefType refType = (RefType) type;
				for (Field f : refType.fields) {
					ST fieldTemplate = templates.load("Field");
					fieldTemplate.add("name", f.name);
					fieldTemplate.add("dstType", f.dstType.name);
					if (f.ghost) {
						fieldTemplate.add("ghost", "true");
					}
					refTypeTemplate.add("fields", fieldTemplate.render());
				}
				template.add("types", refTypeTemplate.render());
			} else {
				template.add("types", type);
			}
		}
		for (Var v : vars) {
			template.add("vars", renderer.render(v));
		}
		// template.add("vars", vars);
		for (Stmt stmt : stmts) {
			String stmtStr = renderer.render(stmt);
			template.add("actions", stmtStr);
		}

		return template.render();
	}
}