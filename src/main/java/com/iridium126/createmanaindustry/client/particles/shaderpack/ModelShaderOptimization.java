package com.iridium126.createmanaindustry.client.particles.shaderpack;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DiscardStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import java.util.*;
import java.util.regex.Pattern;

/** Specializes only the immutable draw context, never pack options or per-instance data.
 * Input is already include-resolved/preprocessed by Iris. No second CPP pass is legal here.
 * Each stage fails open to its exact input, including its resource directives. */
public final class ModelShaderOptimization {
    public record Stages(String vertex, String geometry, String tessControl, String tessEval, String fragment) {}
    public record Plan(Stages stages, List<String> applied, List<String> skipped) {
        public Plan { applied=List.copyOf(applied); skipped=List.copyOf(skipped); }
        public String report() { return "applied="+(applied.isEmpty()?"none":String.join(", ",applied))
                +"; skipped="+String.join(", ",skipped); }
    }
    private record Constant(String type,String value) {}
    // AST printers omit comments; Iris reads these comments as resource instructions.
    private static final Pattern RESOURCE_DIRECTIVE=Pattern.compile("/\\*\\s*(?:DRAWBUFFERS|RENDERTARGETS)\\s*:[\\s\\S]*?\\*/");
    private static final Set<String> PURE_MATH=Set.of("abs","sign","floor","trunc","round","roundEven","ceil","fract",
            "mod","min","max","clamp","mix","step","smoothstep","length","distance","dot","cross","normalize",
            "faceforward","reflect","refract","sin","cos","tan","asin","acos","atan","pow","exp","log","exp2",
            "log2","sqrt","inversesqrt","radians","degrees","transpose","determinant","inverse");
    private ModelShaderOptimization() {}

    public static Plan prepare(Stages input,int entityId,int width,int height) {
        if(width<=0||height<=0)throw new IllegalArgumentException("Invalid model atlas size");
        var constants=new LinkedHashMap<String,Constant>();
        constants.put("entityId",new Constant("int",Integer.toString(entityId)));
        constants.put("blockEntityId",new Constant("int","0"));
        constants.put("currentRenderedItemId",new Constant("int","0"));
        constants.put("atlasSize",new Constant("ivec2","ivec2("+width+","+height+")"));
        constants.put("gtextureSize",new Constant("ivec2","ivec2("+width+","+height+")"));
        var applied=new ArrayList<String>();var skipped=new ArrayList<String>();
        String v=specialize(input.vertex,"vertex",constants,applied,skipped);
        String g=specialize(input.geometry,"geometry",constants,applied,skipped);
        String tc=specialize(input.tessControl,"tess-control",constants,applied,skipped);
        String te=specialize(input.tessEval,"tess-eval",constants,applied,skipped);
        String f=specialize(input.fragment,"fragment",constants,applied,skipped);
        // Iris may test packed metadata. Only existing native discard predicates
        // are moved; no raw-texture alpha test is invented for the pack output.
        return new Plan(new Stages(v,g,tc,te,f),applied,skipped);
    }

    private static String specialize(String source,String stage,Map<String,Constant> constants,
                                     List<String> applied,List<String> skipped) {
        if(source==null)return null;
        // Avoid parsing a large stage with no relevant declarations at all.
        if(constants.keySet().stream().noneMatch(source::contains))return source;
        try {
            var parser=new SingleASTTransformer<>();
            parser.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
            TranslationUnit tree=parser.parseTranslationUnit(parser.getRootSupplier(),source);
            var changes=new ArrayList<String>();
            for(var external:new ArrayList<>(tree.getChildren())) {
                if(!(external instanceof DeclarationExternalDeclaration ed)
                        ||!(ed.getDeclaration() instanceof TypeAndInitDeclaration decl))continue;
                var qualifiers=decl.getType().getTypeQualifier();
                if(qualifiers==null||qualifiers.getParts().size()!=1
                        ||!(qualifiers.getParts().getFirst() instanceof StorageQualifier storage)
                        ||storage.storageType!=StorageQualifier.StorageType.UNIFORM)continue;
                String type=ASTPrinter.printSimple(decl.getType().getTypeSpecifier()).trim();
                for(var member:new ArrayList<>(decl.getMembers())) {
                    String name=member.getName().getName();Constant value=constants.get(name);
                    if(value==null)continue;
                    var references=tree.getRoot().nodeIndex.getStream(ReferenceExpression.class)
                            .filter(ref->ref.getIdentifier().getName().equals(name)).toList();
                    if(references.isEmpty())continue;
                    // The paired driver benchmark found that forcing the main
                    // entity classifier / fragment texture-size branches into a
                    // different schedule can regress dense forward-lit draws.
                    // Keep these uniforms; specialize independent helper context.
                    if(stage.equals("fragment")&&(name.equals("atlasSize")||name.equals("gtextureSize")
                            ||name.equals("entityId")&&references.stream().anyMatch(ref->ref.hasAncestor(tree.getOneMainDefinitionBody())))) {
                        skipped.add(stage+" "+name+": retain native main/sampling schedule");continue;
                    }
                    if(!type.equals(value.type)||member.getArraySpecifier()!=null) {
                        skipped.add(stage+" "+name+": unsupported type/array");continue;
                    }
                    // Replacing the declaration, rather than textual references, respects
                    // function-local shadowing and multi-member uniform declarations.
                    int position=tree.getChildren().indexOf(external);
                    var replacement=parser.parseExternalDeclaration(tree.getRoot(),
                            "const "+type+" "+name+"="+value.value+";");
                    tree.getChildren().add(position,replacement);
                    member.detachAndDelete();changes.add(name);
                }
                if(decl.getMembers().isEmpty())external.detachAndDelete();
            }
            if(stage.equals("fragment")) {
                int moved=hoistNativeDiscards(tree);
                if(moved>0)changes.add("early-alpha("+moved+")");
                else skipped.add("early-alpha: no derivative-safe native discard to move");
            }
            if(changes.isEmpty())return source;
            String output=ASTPrinter.printSimple(tree);
            String directives=RESOURCE_DIRECTIVE.matcher(source).results()
                    .map(match->match.group()).reduce("",(a,b)->a+b+"\n");
            // Comments may precede #version; no executable text is injected ahead of it.
            applied.add(stage+": "+String.join("/",changes));
            return directives+output;
        } catch(RuntimeException failure) {
            skipped.add(stage+": AST unsupported ("+failure.getClass().getSimpleName()+")");
            return source;
        }
    }

    /** Move an existing top-level discard over independent, derivative-free ALU.
     * We never cross a texture fetch, user call, global write, branch or loop.
     * Thus the lanes reaching every original derivative keep the same mask. */
    private static int hoistNativeDiscards(TranslationUnit tree) {
        var main=tree.getOneMainDefinitionBody();if(main==null)return 0;
        var statements=main.getStatements();int moved=0;
        for(int i=1;i<statements.size();i++) {
            if(!(statements.get(i) instanceof SelectionStatement selection)||selection.hasIfFalse()
                    ||!onlyDiscard(selection.getIfTrue()))continue;
            var dependencies=identifiers(selection.getCondition());
            if(!pureCalls(selection.getCondition())||writes(selection.getCondition())!=0)continue;
            int target=i;
            while(target>0&&independentAlu(statements.get(target-1),dependencies,tree))target--;
            if(target<i) {
                String text=ASTPrinter.printSimple(selection);
                selection.detachAndDelete();
                statements.add(target,new SingleASTTransformer<>().parseStatement(tree.getRoot(),text));
                moved++;
            }
        }
        return moved;
    }
    private static boolean onlyDiscard(Statement statement){return statement instanceof DiscardStatement
            ||statement instanceof CompoundStatement c&&c.getStatements().size()==1&&c.getStatements().getFirst() instanceof DiscardStatement;}
    private static Set<String> identifiers(ASTNode node) {
        var result=new HashSet<String>();
        node.getRoot().nodeIndex.getStream(Identifier.class).filter(id->id.hasAncestor(node))
                .forEach(id->result.add(id.getName()));
        return result;
    }
    private static boolean pureCalls(ASTNode node) {
        return node.getRoot().nodeIndex.getStream(FunctionCallExpression.class).filter(call->call==node||call.hasAncestor(node))
                .allMatch(call->call.getFunctionName()==null||PURE_MATH.contains(call.getFunctionName().getName())
                        &&node.getRoot().nodeIndex.getStream(FunctionPrototype.class)
                        .noneMatch(p->p.getName().getName().equals(call.getFunctionName().getName())));
    }
    private static long writes(ASTNode node){return node.getRoot().nodeIndex.getStream(Expression.class)
            .filter(e->e==node||e.hasAncestor(node)).filter(e->{String type=e.getExpressionType().name();
                return type.contains("ASSIGN")||type.contains("INCREMENT")||type.contains("DECREMENT");}).count();}
    private static boolean independentAlu(Statement statement,Set<String> dependencies,TranslationUnit tree) {
        if(!pureCalls(statement))return false;
        if(statement instanceof DeclarationStatement ds&&ds.getDeclaration() instanceof TypeAndInitDeclaration d) {
            return d.getMembers().stream().noneMatch(m->dependencies.contains(m.getName().getName()))
                    &&writes(statement)==0;
        }
        if(statement instanceof ExpressionStatement es&&es.getExpression() instanceof AssignmentExpression a) {
            var lhs=a.getLeft();
            while(lhs instanceof MemberAccessExpression m)lhs=m.getOperand();
            if(!(lhs instanceof ReferenceExpression ref)||dependencies.contains(ref.getIdentifier().getName()))return false;
            String name=ref.getIdentifier().getName();
            // Only a local declared in main can be moved across: output/image/
            // global effects before discard must not disappear.
            return tree.getRoot().nodeIndex.getStream(TypeAndInitDeclaration.class)
                    .filter(d->d.getParent() instanceof DeclarationStatement ds&&ds.getParent()==tree.getOneMainDefinitionBody())
                    .anyMatch(d->d.getMembers().stream().anyMatch(m->m.getName().getName().equals(name)))
                    &&writes(statement)==1;
        }
        return false;
    }
}
