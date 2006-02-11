// Autogenerated AST node
package org.python.parser.ast;
import org.python.parser.SimpleNode;
import java.io.DataOutputStream;
import java.io.IOException;

public class commentType extends SimpleNode {
    public String id;

    public commentType(String id) {
        this.id = id;
    }

    public commentType(String id, SimpleNode parent) {
        this(id);
        this.beginLine = parent.beginLine;
        this.beginColumn = parent.beginColumn;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer("comment[");
        sb.append("id=");
        sb.append(dumpThis(this.id));
        sb.append("]");
        return sb.toString();
    }

    public void pickle(DataOutputStream ostream) throws IOException {
        pickleThis(56, ostream);
        pickleThis(this.id, ostream);
    }

    public Object accept(VisitorIF visitor) throws Exception {
        traverse(visitor);
        return null;
    }

    public void traverse(VisitorIF visitor) throws Exception {
    }

}
