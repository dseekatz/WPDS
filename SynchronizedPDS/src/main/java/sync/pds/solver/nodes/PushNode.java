package sync.pds.solver.nodes;

import sync.pds.solver.SyncPDSSolver.PDSSystem;

public class PushNode<Stmt, Fact, Location> extends Node<Stmt, Fact> {

	private PDSSystem system;
	private Location location;

	public PushNode(Stmt stmt, Fact variable, Location location, PDSSystem system) {
		super(stmt, variable);
		this.system = system;
		this.location = location;
	}

	public PDSSystem system() {
		return system;
	}

	public Location location() {
		return location;
	}

	@Override
	public String toString() {
		return super.toString() + " Push " + location;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((location == null) ? 0 : location.hashCode());
		result = prime * result + ((system == null) ? 0 : system.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		PushNode other = (PushNode) obj;
		if (location == null) {
			if (other.location != null)
				return false;
		} else if (!location.equals(other.location))
			return false;
		if (system != other.system)
			return false;
		return true;
	}
}
