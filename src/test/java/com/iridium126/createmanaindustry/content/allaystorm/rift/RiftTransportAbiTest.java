package com.iridium126.createmanaindustry.content.allaystorm.rift;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

/** Exact descriptors and call-site owners from installed jars; catches required-mixin failures before launch. */
class RiftTransportAbiTest {
    ClassNode read(String name)throws Exception {
        byte[] bytes=null;
        if(name.startsWith("dev/ryanhcode/sable/"))try(var files=Files.list(Path.of("run/mods"))){for(Path path:files.filter(p->p.getFileName().toString().startsWith("sable-neoforge-")).toList())try(var jar=new JarFile(path.toFile())){var entry=jar.getJarEntry(name+".class");if(entry!=null)bytes=jar.getInputStream(entry).readAllBytes();}}
        if(bytes==null)try(var input=getClass().getClassLoader().getResourceAsStream(name+".class")){assertNotNull(input,name);bytes=input.readAllBytes();}
        var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;
    }
    List<AnnotationNode> annotations(List<AnnotationNode> a,List<AnnotationNode> b){var all=new ArrayList<AnnotationNode>();if(a!=null)all.addAll(a);if(b!=null)all.addAll(b);return all;}
    Object value(AnnotationNode annotation,String key){if(annotation.values!=null)for(int i=0;i<annotation.values.size();i+=2)if(annotation.values.get(i).equals(key))return annotation.values.get(i+1);return null;}
    List<MethodNode> methods(ClassNode owner,String selector){int i=selector.indexOf('(');String name=i<0?selector:selector.substring(0,i),desc=i<0?null:selector.substring(i);return owner.methods.stream().filter(m->m.name.equals(name)&&(desc==null||m.desc.equals(desc))).toList();}
    @Test void everyNewRequiredTransportHookHasAnExactTargetAndInvocation()throws Exception {
        List<String> names=List.of("RiftLevelEntitiesAccessor","RiftEntityManagerAccessor","RiftEntityStorageAccessor","RiftPlayerFreezeMixin","RiftTrackingAccessor","RiftTrackedEntityMixin","RiftExternalTravelMixin","RiftDismountMixin","RiftEntityWriteFreezeMixin","RiftPlayerSnapshotMixin","RiftPassengersAccessor","RiftMountStorageMixin","RiftManagedLogoutMixin","RiftEntityStoreObservationMixin","RiftRecoveryBootMixin","create/RiftContraptionAccessor","create/RiftContraptionStopMixin","create/RiftContraptionSnapshotMixin","create/RiftOrientedDismountMixin","create/RiftContraptionWriteFreezeMixin","sable/RiftPlotSnapshotMixin","sable/RiftPhysicsFreezeMixin","sable/RiftSubLevelFreezeMixin","sable/RiftHoldingAccessor","sable/RiftStorageAccessor","sable/RiftHoldingIoMixin","sable/RiftSableTrackingAccessor","sable/RiftSableTrackingMixin","sable/RiftPlotWriteFreezeMixin","sable/RiftPlotTickFreezeMixin","sable/RiftRetainSubLevelsMixin","sable/RiftAllocatedFreezeMixin","sable/RiftSubLevelIntegrityMixin");
        names=new ArrayList<>(names);names.add("sable/RiftScheduledTickFreezeMixin");
        for(String name:names) {
            var mixin=read("com/iridium126/createmanaindustry/mixin/allaystorm/"+name);var annotation=annotations(mixin.visibleAnnotations,mixin.invisibleAnnotations).stream().filter(a->a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
            var targets=new ArrayList<String>();Object classes=value(annotation,"value"),strings=value(annotation,"targets");if(classes instanceof List<?> list)for(Object t:list)targets.add(((Type)t).getInternalName());if(strings instanceof List<?> list)for(Object t:list)targets.add(t.toString().replace('.','/'));
            for(String target:targets){ClassNode owner=read(target);
                for(MethodNode method:mixin.methods)for(var hook:annotations(method.visibleAnnotations,method.invisibleAnnotations)) {
                    if(hook.desc.endsWith("/Accessor;")){String field=(String)value(hook,"value");Type type=Type.getReturnType(method.desc);if(type.equals(Type.VOID_TYPE))type=Type.getArgumentTypes(method.desc)[0];String descriptor=type.getDescriptor();assertTrue(owner.fields.stream().anyMatch(f->f.name.equals(field)&&f.desc.equals(descriptor)),name+" field "+field);}
                    if(hook.desc.endsWith("/Invoker;")){String called=(String)value(hook,"value");assertEquals(1,methods(owner,called+method.desc).size(),name+" invoker "+called+method.desc);}
                    Object selector=value(hook,"method");if(selector==null)continue;List<?> selectors=selector instanceof List<?> list?list:List.of(selector);
                    for(Object select:selectors) {
                        var actual=methods(owner,select.toString());assertFalse(actual.isEmpty(),name+" -> "+select+" missing in "+target);
                        Object ats=value(hook,"at");List<?> atList=ats instanceof List<?> list?list:ats==null?List.of():List.of(ats);
                        for(Object at:atList) {Object call=value((AnnotationNode)at,"target");if(call==null)continue;String reference=call.toString();int end=reference.indexOf(';');String callee=reference.substring(1,end),rest=reference.substring(end+1);int paren=rest.indexOf('(');if(paren<0)continue;String invoked=rest.substring(0,paren),desc=rest.substring(paren);
                            long count=actual.stream().flatMap(m->Arrays.stream(m.instructions.toArray())).filter(i->i instanceof MethodInsnNode m&&m.owner.equals(callee)&&m.name.equals(invoked)&&m.desc.equals(desc)).count();assertEquals(1,count,name+" -> "+select+" call "+reference);
                        }
                    }
                }
            }
        }
    }
}
