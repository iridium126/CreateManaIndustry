package com.iridium126.createmanaindustry.client.particles.shaderpack;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.EqualityExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.InequalityExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanEqualExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanEqualExpression;
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
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
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
    private static final ThreadLocal<Batch> ACTIVE_BATCH=new ThreadLocal<>();
    /** Rebuild-local immutable parse snapshots. Contexts always mutate independent roots. */
    public static final class Batch implements AutoCloseable {
        private final Batch previous=ACTIVE_BATCH.get();
        private final Map<String,TranslationUnit> parsed=new HashMap<>();
        private boolean closed;
        private Batch(){ACTIVE_BATCH.set(this);}
        private TranslationUnit parse(SingleASTTransformer<?> parser,String source){
            var template=parsed.computeIfAbsent(source,key->parser.parseTranslationUnit(parser.getRootSupplier(),key));
            return template.cloneInto(parser.getRootSupplier().get());
        }
        private void remember(String source,TranslationUnit tree){parsed.putIfAbsent(source,tree);}
        @Override public void close(){if(!closed){ACTIVE_BATCH.set(previous);parsed.clear();closed=true;}}
    }
    public static Batch batch(){return new Batch();}
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

    /** Zero overlay is guaranteed by our injected feather arm. Add a uniform arm at
     * the original branch position; sampling, derivatives and alpha tests stay native. */
    public static Plan prepareFeatherBranches(Stages input,boolean exactOverlay){
        if(!exactOverlay||input.geometry!=null||input.tessControl!=null||input.tessEval!=null)
            return new Plan(input,List.of(),List.of("feather: native overlay interface retained"));
        String source=input.fragment;
        if(source==null||!source.contains("entityColor"))
            return new Plan(input,List.of(),List.of("feather: no overlay-dependent material branch"));
        try{
            var parser=new SingleASTTransformer<>();parser.setRootSupplier(RootSupplier.ONLY_NODE_INDEX);
            var batch=ACTIVE_BATCH.get();
            var tree=batch==null?parser.parseTranslationUnit(parser.getRootSupplier(),source):batch.parse(parser,source);
            if(tree.getRoot().nodeIndex.getStream(Identifier.class).anyMatch(id->id.getName().equals("cmi_FeatherPass")))
                return new Plan(input,List.of(),List.of("feather: reserved fragment identifier retained"));
            Identifier declaration=null;
            for(var node:tree.getChildren())if(node instanceof DeclarationExternalDeclaration external&&external.getDeclaration() instanceof TypeAndInitDeclaration type){
                var qualifiers=type.getType().getTypeQualifier();
                if(qualifiers==null||qualifiers.getParts().size()!=1||!(qualifiers.getParts().getFirst() instanceof StorageQualifier q)
                        ||q.storageType!=StorageQualifier.StorageType.IN||!ASTPrinter.printSimple(type.getType().getTypeSpecifier()).trim().equals("vec4"))continue;
                for(var member:type.getMembers())if(member.getName().getName().equals("entityColor")&&member.getArraySpecifier()==null)declaration=member.getName();
            }
            final Identifier binding=declaration;
            if(binding==null||tree.getRoot().nodeIndex.getStream(Identifier.class).anyMatch(id->id.getName().equals("entityColor")&&id!=binding&&!(id.getParent() instanceof ReferenceExpression)))
                return new Plan(input,List.of(),List.of("feather: unknown or shadowed overlay binding"));
            var selections=tree.getRoot().nodeIndex.getStream(SelectionStatement.class)
                    .filter(selection->featherAlphaComparison(selection.getCondition())).toList();
            var outer=selections.stream().filter(selection->selections.stream().noneMatch(parent->parent!=selection&&selection.hasAncestor(parent))).toList();
            if(outer.isEmpty())return new Plan(input,List.of(),List.of("feather: no direct material-branch comparison"));
            int mixes=0;
            boolean userMix=tree.getRoot().nodeIndex.getStream(FunctionPrototype.class).anyMatch(function->function.getName().getName().equals("mix"));
            if(!userMix)for(var call:tree.getRoot().nodeIndex.getStream(FunctionCallExpression.class).toList()){
                var args=call.getParameters();
                if(call.getFunctionName()==null||!call.getFunctionName().getName().equals("mix")||args.size()!=3
                        ||!(args.get(1) instanceof MemberAccessExpression color)||!(color.getOperand() instanceof ReferenceExpression ref)
                        ||!ref.getIdentifier().getName().equals("entityColor")||!Set.of("rgb","xyz").contains(color.getMember().getName())
                        ||!featherAlpha(args.get(2))||!(call.getParent() instanceof AssignmentExpression assignment)||assignment.getRight()!=call
                        ||!(assignment.getParent() instanceof ExpressionStatement statement))continue;
                var fixed=(ExpressionStatement)statement.cloneInto(RootSupplier.ONLY_NODE_INDEX.get());
                var fixedCall=(FunctionCallExpression)((AssignmentExpression)fixed.getExpression()).getRight();
                fixedCall.getParameters().get(1).replaceByAndDelete(parser.parseExpression(fixed.getRoot(),"vec3(0.0)"));
                fixedCall.getParameters().get(2).replaceByAndDelete(parser.parseExpression(fixed.getRoot(),"0.0"));
                statement.replaceByAndDelete(parser.parseStatement(tree.getRoot(),
                        "if(cmi_FeatherPass!=0){"+ASTPrinter.printSimple(fixed)+"}else{"+ASTPrinter.printSimple(statement)+"}"));
                mixes++;
            }
            for(var selection:outer){
                var fixed=(SelectionStatement)selection.cloneInto(RootSupplier.ONLY_NODE_INDEX.get());
                fixed.getRoot().nodeIndex.getStream(MemberAccessExpression.class)
                        .filter(member->member.hasAncestor(fixed.getCondition())&&featherAlpha(member)).toList()
                        .forEach(member->member.replaceByAndDelete(parser.parseExpression(fixed.getRoot(),"0.0")));
                // GLSL evaluates the original literal/type rules, including underflow;
                // no CPU floating-point comparison decides the native branch.
                String replacement="if(cmi_FeatherPass!=0){"+ASTPrinter.printSimple(fixed)+"}else{"+ASTPrinter.printSimple(selection)+"}";
                selection.replaceByAndDelete(parser.parseStatement(tree.getRoot(),replacement));
            }
            int at=0;while(at<tree.getChildren().size()&&!(tree.getChildren().get(at) instanceof FunctionDefinition))at++;
            tree.getChildren().add(at,parser.parseExternalDeclaration(tree.getRoot(),"uniform int cmi_FeatherPass;"));
            String directives=RESOURCE_DIRECTIVE.matcher(source).results().map(match->match.group()).reduce("",(a,b)->a+b+"\n");
            String result=directives+ASTPrinter.printSimple(tree);
            if(batch!=null)batch.remember(result,tree);
            return new Plan(new Stages(input.vertex,input.geometry,input.tessControl,input.tessEval,result),
                    List.of("feather: uniform material branches("+outer.size()+"), native mixes("+mixes+")"),List.of());
        }catch(RuntimeException failure){
            return new Plan(input,List.of(),List.of("feather: AST unsupported ("+failure.getClass().getSimpleName()+")"));
        }
    }
    private static boolean featherAlpha(Expression expression){
        return expression instanceof MemberAccessExpression member&&member.getOperand() instanceof ReferenceExpression ref
                &&ref.getIdentifier().getName().equals("entityColor")&&Set.of("a","w").contains(member.getMember().getName());
    }
    private static boolean featherAlphaComparison(Expression expression){
        if(!(expression instanceof BinaryExpression binary)||!Set.of("EQUAL","NOT_EQUAL","LESS_THAN","LESS_THAN_EQUAL","GREATER_THAN","GREATER_THAN_EQUAL").contains(binary.getExpressionType().name()))return false;
        Expression literal=featherAlpha(binary.getLeft())?binary.getRight():featherAlpha(binary.getRight())?binary.getLeft():null;
        return literal instanceof LiteralExpression number&&(number.isInteger()||number.isFloatingPoint())&&Double.isFinite(number.getNumber().doubleValue());
    }

    private static String specialize(String source,String stage,Map<String,Constant> constants,
                                     List<String> applied,List<String> skipped) {
        if(source==null)return null;
        // Avoid parsing a large stage with no relevant declarations at all.
        if(constants.keySet().stream().noneMatch(source::contains))return source;
        try {
            var parser=new SingleASTTransformer<>();
            // This pass queries nodes only; building prefix/name/declaration indexes is unnecessary.
            parser.setRootSupplier(RootSupplier.ONLY_NODE_INDEX);
            Batch batch=ACTIVE_BATCH.get();
            TranslationUnit tree=batch==null?parser.parseTranslationUnit(parser.getRootSupplier(),source):batch.parse(parser,source);
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
                            ||name.equals("entityId")&&references.stream().anyMatch(ref->ref.hasAncestor(mainBody(tree))))) {
                        if(name.equals("entityId")&&type.equals(value.type)&&member.getArraySpecifier()==null){
                            int folded=foldComparisons(tree,parser,name,value,member.getName());
                            if(folded>0)changes.add("fixed-comparisons "+name+"("+folded+")");
                        }
                        skipped.add(stage+" "+name+": retain native uniform/sampling schedule");continue;
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
            String result=directives+output;
            if(batch!=null)batch.remember(result,tree);
            return result;
        } catch(RuntimeException failure) {
            skipped.add(stage+": AST unsupported ("+failure.getClass().getSimpleName()+")");
            return source;
        }
    }

    /** No substitution of arithmetic or calls. Ambiguous/shadowed bindings fail open. */
    private static int foldComparisons(TranslationUnit tree,SingleASTTransformer<?> parser,String name,Constant value,Identifier declaration){
        if(tree.getRoot().nodeIndex.getStream(Identifier.class).anyMatch(id->id.getName().equals(name)&&id!=declaration&&!(id.getParent() instanceof ReferenceExpression)))return 0;
        int folded=0;
        var comparisons=new ArrayList<BinaryExpression>();
        for(Class<? extends BinaryExpression> type:List.of(EqualityExpression.class,InequalityExpression.class,LessThanExpression.class,GreaterThanExpression.class,LessThanEqualExpression.class,GreaterThanEqualExpression.class))
            tree.getRoot().nodeIndex.getStream(type).forEach(comparisons::add);
        for(var e:comparisons){
            String op=e.getExpressionType().name();
            if(!Set.of("EQUAL","NOT_EQUAL","LESS_THAN","LESS_THAN_EQUAL","GREATER_THAN","GREATER_THAN_EQUAL").contains(op))continue;
            Double a=contextNumber(e.getLeft(),name,value),b=contextNumber(e.getRight(),name,value);
            if(a==null||b==null||!Double.isFinite(a)||!Double.isFinite(b)||Math.abs(a)>16777216||Math.abs(b)>16777216)continue;
            if(e.getLeft() instanceof LiteralExpression&&e.getRight() instanceof LiteralExpression)continue;
            boolean result=switch(op){case "EQUAL"->a.doubleValue()==b.doubleValue();case "NOT_EQUAL"->a.doubleValue()!=b.doubleValue();case "LESS_THAN"->a<b;case "LESS_THAN_EQUAL"->a<=b;case "GREATER_THAN"->a>b;default->a>=b;};
            e.replaceByAndDelete(parser.parseExpression(tree.getRoot(),result?"true":"false"));folded++;
        }return folded;
    }
    private static Double contextNumber(Expression e,String name,Constant value){
        if(e instanceof LiteralExpression n){
            if(n.isInteger())return n.getNumberType()==io.github.douira.glsl_transformer.util.Type.NumberType.SIGNED_INTEGER?(double)n.getInteger():null;
            if(n.isFloatingPoint()){double number=n.getFloating();return number==(double)(float)number?number:null;}
            return null;
        }
        if(e instanceof ReferenceExpression r&&r.getIdentifier().getName().equals(name)&&value.type.equals("int"))return Double.valueOf(value.value);
        if(e instanceof MemberAccessExpression m&&m.getOperand() instanceof ReferenceExpression r&&r.getIdentifier().getName().equals(name)&&value.type.equals("ivec2")){
            String field=m.getMember().getName();if(!Set.of("x","y","r","g","s","t").contains(field))return null;
            String[] xy=value.value.substring(6,value.value.length()-1).split(",");return Double.valueOf(xy[Set.of("x","r","s").contains(field)?0:1]);
        }return null;
    }

    private static CompoundStatement mainBody(TranslationUnit tree){
        return tree.getRoot().nodeIndex.getStream(FunctionDefinition.class)
                .filter(function->function.getFunctionPrototype().getName().getName().equals("main"))
                .findFirst().orElseThrow(()->new IllegalStateException("No main definition")).getBody();
    }

    /** Move an existing top-level discard over independent, derivative-free ALU.
     * We never cross a texture fetch, user call, global write, branch or loop.
     * Thus the lanes reaching every original derivative keep the same mask. */
    private static int hoistNativeDiscards(TranslationUnit tree) {
        var main=mainBody(tree);if(main==null)return 0;
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
    private static List<Expression> expressions(ASTNode node){
        var result=new ArrayList<Expression>();
        io.github.douira.glsl_transformer.ast.traversal.ASTWalker.walk(new io.github.douira.glsl_transformer.ast.traversal.ASTListener(){
            @Override public void enterEveryNode(io.github.douira.glsl_transformer.ast.node.abstract_node.InnerASTNode value){if(value instanceof Expression e)result.add(e);}
        },node);return result;
    }
    private static long writes(ASTNode node){return expressions(node).stream().filter(e->{String type=e.getExpressionType().name();
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
                    .filter(d->d.getParent() instanceof DeclarationStatement ds&&ds.getParent()==mainBody(tree))
                    .anyMatch(d->d.getMembers().stream().anyMatch(m->m.getName().getName().equals(name)))
                    &&writes(statement)==1;
        }
        return false;
    }
}
