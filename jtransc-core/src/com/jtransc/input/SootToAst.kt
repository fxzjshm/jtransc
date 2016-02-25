package com.jtransc.input

import com.jtransc.ast.*
import com.jtransc.ds.toTypedArray2
import com.jtransc.ds.zipped
import com.jtransc.env.OS
import com.jtransc.error.InvalidOperationException
import com.jtransc.error.invalidOp
import com.jtransc.error.noImpl
import com.jtransc.time.measureTime
import com.jtransc.vfs.MergedLocalAndJars
import com.jtransc.vfs.SyncVfsFile
import jtransc.annotation.*
import soot.*
import soot.jimple.*
import soot.options.Options
import soot.tagkit.*
import java.io.File
import java.nio.charset.Charset
import java.util.*
import kotlin.reflect.KProperty1

class SootToAst {
	//registerAbstract("java.lang.Object", "Object", "ObjectTools")
	//registerAbstract("java.lang.String", "String", "StringTools")

	companion object {
		fun createProgramAst(classNames: List<String>, mainClass: String, classPaths: List<String>, outputPath: SyncVfsFile, deps2: Set<AstRef>? = null): AstProgram {
			SootUtils.init(classPaths)
			return SootToAst().generateProgram(BaseProjectContext(classNames, mainClass, classPaths, outputPath, deps2?.toHashSet()))
		}

		fun checkIfClassExists(name: FqName): Boolean {
			try {
				return Scene.v().getSootClass(name.fqname) != null
			} catch (t: Throwable) {
				return false
			}
		}
	}

	protected val cl = this.javaClass.classLoader
	protected val utf8 = Charset.forName("UTF-8")
	protected val file_separator = System.getProperty("file.separator")
	protected val tree = TargetTree()

	protected fun classNameToPath(name: String): String = name.replace(".", "/")

	fun generateProgram(projectContext: BaseProjectContext): AstProgram {
		val program = AstProgram(
			entrypoint = FqName(projectContext.mainClass),
			resourcesVfs = MergedLocalAndJars(projectContext.classPaths)
		)

		// Load classes into stage
		projectContext.classNames.forEach {
			Scene.v().loadClassAndSupport(it)
		}

		// Preprocesses classes
		projectContext.classNames.forEach { tree.getTargetClass(it) }

		print("Processing classes...")
		val context = SootContext(projectContext, program)

		projectContext.classNames.forEach { context.addClassToGenerate(it) }

		val (elapsed) = measureTime {
			while (context.hasClassToGenerate()) {
				val className = context.getClassToGenerate()
				val clazz = tree.getTargetClass(className)
				//val nativeClassTag = clazz.clazz.getTag("libcore.NativeClass", "")

				//print("Processing class: " + clazz.clazz.name + "...")

				val generatedClass = generateClass(program, clazz.clazz, context)
				program.add(generatedClass)
				context.addGeneratedClass(generatedClass)

				// Add dependencies for annotations
				// @TODO: Do this better!
				// @TODO: This should be recursive. But anyway it shouldn't be there!
				// @TODO: Analyzer should detect annotations and reference these ones
				generatedClass.classAndFieldAndMethodAnnotations.forEach {
					context.addClassToGenerate(it.type.name.fqname)
					val clazz2 = context.getClass(it.type.name)
					context.projectContext.addDep(clazz2.clazz.astRef)
					for (m in clazz2.clazz.methods) {
						context.projectContext.addDep(m.astRef)
						context.projectContext.addDep(m.returnType.astType.getRefClasses())
						for (clazz in m.returnType.astType.getRefClasses()) {
							context.addClassToGenerate(clazz.fqname)
						}
					}
				}

				//val referencedClass = generatedClass.resolveAnnotation(JTranscReferenceClass::value)
				//if (referencedClass != null) {
				//	context.projectContext.addDep(AstClassRef(FqName(referencedClass)))
				//}
				//println("Ok($elapsed):$nativeClassTag")
			}
		}

		//for (dep in context.projectContext.deps2!!) println(dep)

		println("Ok classes=${projectContext.classNames.size}, time=$elapsed")

		return program
	}

	class SootContext(val projectContext: BaseProjectContext, val program: AstProgram) {
		private val classesToGenerateSet = hashSetOf<String>()
		private val classesToGenerateOnce: Queue<String> = LinkedList()
		val generatedClassesList = arrayListOf<AstClass>()

		fun hasClassToGenerate() = classesToGenerateOnce.isNotEmpty()

		fun getClassToGenerate() = classesToGenerateOnce.remove()

		fun addClassToGenerate(className: String) {
			if (classesToGenerateSet.contains(className)) return
			classesToGenerateSet.add(className)
			classesToGenerateOnce.add(className)
		}

		fun addGeneratedClass(clazz: AstClass) {
			generatedClassesList.add(clazz)
		}

		class SootClassContext(val context: SootContext, val clazz: SootClass) {
			val clazzName = FqName(clazz.name)
			val parent = if (clazz.hasSuperclass()) clazz.superclass else null
			val parentContext = if (parent != null) context[parent] else null
			val clazzAbstract = clazz.isClassAbstract
			val allImplementedMethods by lazy { clazz.getAllImplementedMethods() }
			val allMethodsToImplement by lazy { clazz.getAllMethodsToImplement() }
			val missingMethods by lazy {
				val list = allMethodsToImplement.map { it.withoutClass } - allImplementedMethods.map { it.withoutClass }
				list.map { it.withClass(clazzName).toEmptyMethod(context.program) }
			}
			val ancestorsWithThis = clazz.getAncestors(includeThis = true)
			val ancestorsWithoutThis = clazz.getAncestors(includeThis = false)
			val abstractAncestorsWithThis by lazy { ancestorsWithThis.filter { it.isClassAbstract } }
			val abstractAncestorsWithoutThis by lazy { ancestorsWithoutThis.filter { it.isClassAbstract } }
			val allMissingMethods by lazy { abstractAncestorsWithoutThis.flatMap { context[it].missingMethods } }
		}

		class SootMethodContext(val context: SootContext, val method: SootMethod) {
			val clazz = method.declaringClass
			val clazzContext = context[clazz]
			val methodName = method.name
			val overridingMethod by lazy {
				var override = if (clazz.isInterface) {
					null
				} else {
					method.overridingMethod
				}
				if (override == null) {
					val allMissingMethods = clazzContext.allMissingMethods
					if (allMissingMethods.isNotEmpty()) {
						override = allMissingMethods.firstOrNull { it.ref.withoutClass == method.astRef.withoutClass }?.ref
					}
				}
				override
			}
		}

		val classes = hashMapOf<String, SootClassContext>()
		val methods = hashMapOf<SootMethod, SootMethodContext>()

		operator fun get(clazz: SootClass): SootClassContext {
			val clazzName = clazz.name
			if (clazzName !in classes) classes[clazzName] = SootClassContext(this, clazz)
			return classes[clazzName]!!
		}

		operator fun get(method: SootMethod): SootMethodContext {
			if (method !in methods) methods[method] = SootMethodContext(this, method)
			return methods[method]!!
		}

		fun mustInclude(method: SootMethod): Boolean {
			//if (method.declaringClass.isInterface) return true
			//if (method.isMethodOverriding && method.name != "<init>") return true
			//if (method.name == "toString") return true
			return projectContext.mustInclude(method.astRef)
			//return true
		}

		fun mustInclude(method: SootField): Boolean {
			return true
		}

		fun getClass(name: FqName): SootClassContext {
			//return this[Scene.v().getSootClass(name.fqname)]
			return this[Scene.v().loadClassAndSupport(name.fqname)]
		}
	}

	fun generateMethod(containingClass: AstClass, method: SootMethod, context: SootContext): AstMethod {
		val methodRef = method.astRef
		val methodContext = context[method]
		val body = if (method.isConcrete) AstMethodProcessor.processBody(method, context) else null
		val annotations = method.tags.toAstAnnotations()

		return AstMethod(
			containingClass = containingClass,
			annotations = annotations,
			name = methodRef.name,
			type = methodRef.type,
			body = body,
			signature = method.astType.mangle(),
			genericSignature = method.tags.filterIsInstance<SignatureTag>().firstOrNull()?.signature,
			defaultTag = method.tags.filterIsInstance<AnnotationDefaultTag>().firstOrNull()?.toAstAnnotation(),
			modifiers = method.modifiers,
			isExtraAdded = false,
			isStatic = method.isStatic,
			visibility = method.astVisibility,
			overridingMethod = methodContext.overridingMethod,
			isImplementing = method.isMethodImplementing,
			isNative = method.isNative
			//nativeMethodBody = method.getAnnotation(JTranscMethodBody::value),
		)
	}

	fun AnnotationElem.unboxAnnotationElement(): Pair<String, Any?> {
		val it = this
		return it.name to when (it) {
			is AnnotationBooleanElem -> it.value
			is AnnotationClassElem -> throw NotImplementedError("Unhandled annotation element type $it")
			is AnnotationAnnotationElem -> throw NotImplementedError("Unhandled annotation element type $it")
			is AnnotationEnumElem -> {
				val type = AstType.demangle(it.typeName) as AstType.REF
				AstFieldRef(type.name, it.constantName, type)
			}
			is AnnotationArrayElem -> it.values.map { it.unboxAnnotationElement() }
			is AnnotationFloatElem -> it.value
			is AnnotationDoubleElem -> it.value
			is AnnotationIntElem -> it.value
			is AnnotationLongElem -> it.value
			is AnnotationStringElem -> it.value
			else -> throw NotImplementedError("Unhandled annotation element type $it")
		}
	}

	fun AnnotationDefaultTag.toAstAnnotation() = this.defaultVal.unboxAnnotationElement()

	fun generateField(containingClass: AstClass, field: SootField, context: SootContext): AstField {
		val it = field
		val constantValue = it.tags.filterIsInstance<ConstantValueTag>().firstOrNull()
		val hasConstantValue = constantValue != null
		val annotations = it.tags.toAstAnnotations()
		val finalConstantValue: Any? = when (constantValue) {
			null -> null
			is IntegerConstantValueTag -> constantValue.intValue
			is LongConstantValueTag -> constantValue.longValue
			is DoubleConstantValueTag -> constantValue.doubleValue
			is FloatConstantValueTag -> constantValue.floatValue
			is StringConstantValueTag -> constantValue.stringValue
			else -> throw InvalidOperationException("Not a valid constant")
		}


		return AstField(
			containingClass = containingClass,
			name = it.name,
			annotations = annotations,
			type = it.type.astType,
			descriptor = it.type.astType.mangle(),
			genericSignature = it.tags.filterIsInstance<SignatureTag>().firstOrNull()?.signature,
			modifiers = it.modifiers,
			isStatic = it.isStatic,
			isFinal = it.isFinal,
			hasConstantValue = hasConstantValue,
			constantValue = finalConstantValue,
			visibility = if (it.isPublic) AstVisibility.PUBLIC else if (it.isProtected) AstVisibility.PROTECTED else AstVisibility.PRIVATE
		)
	}

	fun generateClass(program: AstProgram, sootClass: SootClass, context: SootContext): AstClass {
		val clazzName = FqName(sootClass.name)
		val annotations = sootClass.tags.toAstAnnotations()
		val contextClass = context[sootClass]
		val astClass = AstClass(
			program = context.program,
			name = clazzName,
			modifiers = sootClass.modifiers,
			annotations = annotations,
			classType = if (sootClass.isInterface) AstClassType.INTERFACE else if (sootClass.isAbstract) AstClassType.ABSTRACT else AstClassType.CLASS,
			visibility = AstVisibility.PUBLIC,
			extending = if (sootClass.hasSuperclass() && !sootClass.isInterface) FqName(sootClass.superclass.name) else null,
			implementing = sootClass.interfaces.map { FqName(it.name) }
		)

		if (contextClass.clazzAbstract) {
			for (method in contextClass.missingMethods) astClass.add(method)
		}

		for (method in sootClass.methods.filter { context.mustInclude(it) }.map { generateMethod(astClass, it, context) }) {
			astClass.add(method)
		}

		for (method in sootClass.fields.filter { context.mustInclude(it) }.map { generateField(astClass, it, context) }) {
			astClass.add(method)
		}

		astClass.finish()

		return astClass
	}
}

class TargetTree {
	private val targetClasses = hashMapOf<SootClass, TargetClass>()

	fun getTargetClass(name: String): TargetClass {
		//return getTargetClass(Scene.v().getSootClass(name))
		return getTargetClass(Scene.v().loadClassAndSupport(name))
	}

	fun getTargetClass(clazz: SootClass): TargetClass {
		//if (!targetClasses.contains(clazz)) targetClasses.put(clazz, new TargetClass(this, clazz))
		//targetClasses.get(clazz).orNull
		return TargetClass(this, clazz)
	}

}

class TargetMethod(val tree: TargetTree, val clazz: TargetClass, val method: SootMethod) {
	val signature by lazy {
		method.name + "_" + method.parameterTypes.map { (it as Type).toString() }.joinToString(",") + method.returnType.toString()
	}
}

class TargetField(val tree: TargetTree, val clazz: TargetClass, val field: SootField) {

}

class TargetClass(val tree: TargetTree, val clazz: SootClass) {
	val methods = hashMapOf<String, TargetMethod>()
	val fields = hashMapOf<String, TargetField>()

	init {
		for (sootMethod in clazz.methods) {
			val targetMethod = TargetMethod(tree, this, sootMethod)
			methods[targetMethod.signature] = targetMethod
		}
	}
}

open class BaseProjectContext(val classNames: List<String>, val mainClass: String, val classPaths: List<String>, val output: SyncVfsFile, val deps2: HashSet<AstRef>?) {
	val classes = arrayListOf<AstClass>()
	val preInitLines = arrayListOf<String>()
	val bootImports = arrayListOf<String>()

	fun mustInclude(ref: AstRef): Boolean = if (deps2 == null) true else ref in deps2
	fun addDep(dep: AstRef) {
		this.deps2?.add(dep)
	}

	fun addDep(dep: Iterable<AstRef>) {
		this.deps2?.addAll(dep)
	}
	//def getClassesWithStaticConstructor
}

/*
@Retention(AnnotationRetention.RUNTIME)
annotation class DemoAnnotation(val a:String)
@DemoAnnotation("test")
class MyTest {
}
*/

open class AstMethodProcessor private constructor(
	private val method: SootMethod,
	private val context: SootToAst.SootContext
) {
	companion object {
		fun processBody(method: SootMethod, context: SootToAst.SootContext): AstBody {
			return AstMethodProcessor(method, context).handle()
		}
	}

	private val activeBody = method.retrieveActiveBody()
	private val units = activeBody.units.toList()
	private val traps = activeBody.traps.toList()

	private var labelIndex = 0
	private val labels = hashMapOf<soot.Unit, AstLabel>()

	private val locals = hashMapOf<String, AstLocal>()

	private fun ensureLabel(unit: soot.Unit): AstLabel {
		if (unit !in labels) {
			labelIndex++
			labels[unit] = AstLabel("label_$labelIndex")
		}
		return labels[unit]!!
	}

	private fun ensureLocal(c: soot.Local): AstLocal {
		if (locals[c.name] == null) {
			locals[c.name] = AstLocal(c.index, c.name, c.type.astType)
		}
		return locals[c.name]!!
	}

	private fun handle(): AstBody {
		prepareInternal(units)
		val stm = handleInternal(units)
		return AstBody(
			stm = stm,
			locals = locals.values.sortedBy { it.name },
			traps = traps.map { AstTrap(ensureLabel(it.beginUnit), ensureLabel(it.endUnit), ensureLabel(it.handlerUnit), it.exception.astType) }
		)
	}

	private fun prepareInternal(units: List<soot.Unit>) {
		for (trap in traps) {
			ensureLabel(trap.beginUnit)
			ensureLabel(trap.endUnit)
			ensureLabel(trap.handlerUnit)
		}
		for (unit in units) {
			when (unit) {
				is IfStmt -> ensureLabel(unit.target)
				is GotoStmt -> ensureLabel(unit.target)
				is LookupSwitchStmt -> {
					unit.targets.map { it as soot.Unit }.forEach { ensureLabel(it) }
					ensureLabel(unit.defaultTarget)
				}
				is TableSwitchStmt -> {
					unit.targets.map { it as soot.Unit }.forEach { ensureLabel(it) }
					ensureLabel(unit.defaultTarget)
				}
				else -> {

				}
			}
		}
	}

	private fun handleInternal(units: List<soot.Unit>): AstStm {
		var stms = arrayListOf<AstStm>()
		for (unit in units) {
			if (unit in labels) stms.add(AstStm.STM_LABEL(labels[unit]!!))
			stms.add(this.convert(unit))
		}
		return AstStm.STMS(stms.toList())
	}

	private fun convert(s: soot.Unit): AstStm = when (s) {
		is DefinitionStmt -> {
			val l = convert(s.leftOp)
			val r = convert(s.rightOp)
			when (l) {
				is AstExpr.LOCAL -> AstStm.SET(l.local, r)
				is AstExpr.ARRAY_ACCESS -> AstStm.SET_ARRAY((l.array as AstExpr.LOCAL).local, l.index, r)
				is AstExpr.STATIC_FIELD_ACCESS -> AstStm.SET_FIELD_STATIC(l.clazzName, l.field, r, l.isInterface)
				is AstExpr.INSTANCE_FIELD_ACCESS -> {
					AstStm.SET_FIELD_INSTANCE(l.expr, l.field, r)
				}
				else -> invalidOp("Can't handle leftOp: $l")
			}
		}
		is ReturnStmt -> AstStm.RETURN(convert(s.op))
		is ReturnVoidStmt -> AstStm.RETURN(null)
		is IfStmt -> AstStm.IF_GOTO(convert(s.condition), ensureLabel(s.target))
		is GotoStmt -> AstStm.GOTO(ensureLabel(s.target))
		is ThrowStmt -> AstStm.THROW(convert(s.op))
		is InvokeStmt -> AstStm.STM_EXPR(convert(s.invokeExpr))
		is EnterMonitorStmt -> AstStm.MONITOR_ENTER(convert(s.op))
		is ExitMonitorStmt -> AstStm.MONITOR_EXIT(convert(s.op))
		is NopStmt -> AstStm.STMS(listOf())
		is LookupSwitchStmt -> AstStm.SWITCH_GOTO(
			convert(s.key),
			ensureLabel(s.defaultTarget),
			(0 until s.targetCount).map {
				val (key, label) = Pair(s.getLookupValue(it), s.getTarget(it))
				Pair(key, ensureLabel(label))
			}//.uniqueMap()
		)
		is TableSwitchStmt -> AstStm.SWITCH_GOTO(
			convert(s.key),
			ensureLabel(s.defaultTarget),
			(s.lowIndex..s.highIndex).map {
				Pair(it, ensureLabel(s.getTarget(it - s.lowIndex)))
			}//.uniqueMap()
		)
		else -> throw RuntimeException()
	}

	private fun simplify(expr: AstExpr): AstExpr {
		if ((expr is AstExpr.CAST) && (expr.expr is AstExpr.LITERAL) && (expr.from == AstType.INT) && (expr.to == AstType.BOOL)) {
			return AstExpr.LITERAL(expr.expr.value != 0)
		}
		// No simplified!
		return expr
	}

	private fun convert(c: Value): AstExpr = when (c) {
		is Local -> AstExpr.LOCAL(ensureLocal(c))
		is NullConstant -> AstExpr.LITERAL(null)
		is IntConstant -> AstExpr.LITERAL(c.value)
		is LongConstant -> AstExpr.LITERAL(c.value)
		is FloatConstant -> AstExpr.LITERAL(c.value)
		is DoubleConstant -> AstExpr.LITERAL(c.value)
		is StringConstant -> AstExpr.LITERAL(c.value)
		is ClassConstant -> {
			val className = c.value.replace('/', '.')
			if (className.startsWith("[")) {
				AstExpr.CLASS_CONSTANT(AstType.demangle(className))
			} else {
				AstExpr.CLASS_CONSTANT(AstType.REF(className))
			}
		}
		is ThisRef -> AstExpr.THIS(FqName(method.declaringClass.name))
		is ParameterRef -> AstExpr.PARAM(AstArgument(c.index, c.type.astType))
		is CaughtExceptionRef -> AstExpr.CAUGHT_EXCEPTION(c.type.astType)
		is ArrayRef -> AstExpr.ARRAY_ACCESS(convert(c.base), convert(c.index))
		is InstanceFieldRef -> AstExpr.INSTANCE_FIELD_ACCESS(convert(c.base), c.field.ast, c.field.type.astType)
		is StaticFieldRef -> AstExpr.STATIC_FIELD_ACCESS(AstType.REF(c.field.declaringClass.name), c.field.ast, c.field.type.astType, c.field.declaringClass.isInterface)
		is CastExpr -> AstExpr.CAST(c.castType.astType, convert(c.op))
		is InstanceOfExpr -> AstExpr.INSTANCE_OF(convert(c.op), c.checkType.astType)
		is NewExpr -> AstExpr.NEW(c.type.astType as AstType.REF)
		is NewArrayExpr -> AstExpr.NEW_ARRAY(c.baseType.astType, listOf(convert(c.size)))
		is NewMultiArrayExpr -> AstExpr.NEW_ARRAY(c.baseType.astType, (0 until c.sizeCount).map { convert(c.getSize(it)) })
		is LengthExpr -> AstExpr.ARRAY_LENGTH(convert(c.op))
		is NegExpr -> AstExpr.UNOP(AstUnop.NEG, convert(c.op))
		is BinopExpr -> {
			// @TODO: Make this generic!
			val destType = c.type.astType
			val l = convert(c.op1)
			val r = convert(c.op2)
			val lType = l.type
			val rType = r.type
			val op = c.getAstOp(lType, rType)
			if (c.op1.type is BooleanType && (op.symbol == "==") && (c.op2.type is IntType)) {
				AstExpr.BINOP(destType, l, op, simplify(AstExpr.CAST(AstType.BOOL, r)))
			} else {
				AstExpr.BINOP(destType, l, op, r)
			}
		}
		is InvokeExpr -> {
			val argsList = c.args.toList()
			val castTypes = c.method.parameterTypes.map { it as Type }
			val args = Pair(argsList, castTypes).zipped.map {
				val (value, expectedType) = it
				doCastIfNeeded(expectedType, value)
			}.toList()
			val i = c
			when (i) {
				is StaticInvokeExpr -> {
					AstExpr.CALL_STATIC(AstType.REF(c.method.declaringClass.name), c.method.astRef, args)
				}
				is InstanceInvokeExpr -> {
					val isSpecial = i is SpecialInvokeExpr
					val obj = convert(i.base)
					val method = c.method.astRef
					val objType = obj.type
					var castToObject = false
					if (isSpecial && ((obj.type as AstType.REF).name != method.containingClass)) {
						AstExpr.CALL_SUPER(obj, method.containingClass, method, args, isSpecial)
					} else {
						if (objType is AstType.ARRAY) {
							castToObject = true
						} else if (objType is AstType.REF && context.getClass(objType.name).clazz.isInterface) {
							castToObject = true
						}
						val obj2 = if (castToObject) AstExpr.CAST(method.classRef.type, obj) else obj
						AstExpr.CALL_INSTANCE(obj2, method, args, isSpecial)
					}
				}
				else -> throw RuntimeException()
			}
		}
		else -> throw RuntimeException()
	}

	final fun doCastIfNeeded(toType: Type, value: Value): AstExpr = if (value.type == toType) {
		convert(value)
	} else {
		AstExpr.CAST(value.type.astType, toType.astType, convert(value))
	}
}

fun BinopExpr.getAstOp(l: AstType, r: AstType): AstBinop {
	return when (this) {
		is AddExpr -> AstBinop.ADD
		is SubExpr -> AstBinop.SUB
		is MulExpr -> AstBinop.MUL
		is DivExpr -> AstBinop.DIV
		is RemExpr -> AstBinop.REM
		is AndExpr -> AstBinop.AND
		is OrExpr -> AstBinop.OR
		is XorExpr -> AstBinop.XOR
		is ShlExpr -> AstBinop.SHL
		is ShrExpr -> AstBinop.SHR
		is UshrExpr -> AstBinop.USHR
		is EqExpr -> AstBinop.EQ
		is NeExpr -> AstBinop.NE
		is GeExpr -> AstBinop.GE
		is LeExpr -> AstBinop.LE
		is LtExpr -> AstBinop.LT
		is GtExpr -> AstBinop.GT
		is CmpExpr -> if (l == AstType.LONG) AstBinop.LCMP else AstBinop.CMP
		is CmplExpr -> AstBinop.CMPL
		is CmpgExpr -> AstBinop.CMPG
		else -> throw RuntimeException()
	}
}

val SootClass.astRef: AstClassRef get() = AstClassRef(this.name)
val SootMethod.astRef: AstMethodRef get() = AstMethodRef(
	FqName(this.declaringClass.name),
	this.name,
	AstType.METHOD_TYPE(
		this.parameterTypes.withIndex().map {
			val (index, type) = it
			AstArgument(index, (type as Type).astType)
		},
		this.returnType.astType
	)
)

val SootMethod.astType: AstType.METHOD_TYPE get() = AstType.METHOD_TYPE(this.returnType.astType, this.parameterTypes.map { (it as Type).astType })

val SootClass.astType: AstType.REF get() = this.type.astType as AstType.REF

val Type.astType: AstType get() = when (this) {
	is VoidType -> AstType.VOID
	is BooleanType -> AstType.BOOL
	is ByteType -> AstType.BYTE
	is CharType -> AstType.CHAR
	is ShortType -> AstType.SHORT
	is IntType -> AstType.INT
	is FloatType -> AstType.FLOAT
	is DoubleType -> AstType.DOUBLE
	is LongType -> AstType.LONG
	is ArrayType -> AstType.ARRAY(baseType.astType, numDimensions)
	is RefType -> AstType.REF(FqName(className))
	is NullType -> AstType.NULL
	else -> throw NotImplementedError("toAstType: $this")
}

val SootField.ast: AstFieldRef get() = AstFieldRef(
	FqName(this.declaringClass.name),
	this.name,
	this.type.astType
)

val FieldRef.ast: AstFieldRef get() = this.field.ast

val SootMethod.astVisibility: AstVisibility get() = if (this.isPublic) {
	AstVisibility.PUBLIC
} else if (this.isProtected) {
	AstVisibility.PROTECTED
} else {
	AstVisibility.PRIVATE
}

object SootUtils {
	fun init(classPaths: List<String>): Unit {
		G.reset()

		val Options_v = Options.v()

		//Options.v().set_output_format(Options.output_format_jimple)
		Options.v().set_output_format(Options.output_format_shimple)
		//Options.v().set_output_format(Options.output_format_grimple)
		Options_v.set_include_all(true)
		Options_v.set_print_tags_in_output(true)

		Options_v.set_allow_phantom_refs(false)
		//Options_v.set_allow_phantom_refs(true)

		Options_v.set_keep_line_number(true)

		val file_separator = OS.fileSeparator

		println("file_separator: $file_separator ... PathSeparator: ${File.pathSeparator}")

		Options_v.set_soot_classpath(classPaths.joinToString(File.pathSeparator))

		Options_v.setPhaseOption("jb.dae", "enabled:false")
		Options_v.setPhaseOption("jb.uce", "enabled:false")
		Options_v.setPhaseOption("jap.npc", "enabled:true")
		Options_v.setPhaseOption("jap.abc", "enabled:true")
		Options_v.setPhaseOption("jop", "enabled:true")
		Options_v.setPhaseOption("jop.cse", "enabled:false")
		Options_v.setPhaseOption("jop.bcm", "enabled:false")
		Options_v.setPhaseOption("jop.lcm", "enabled:false")
		Options_v.setPhaseOption("jop.cp", "enabled:false")
		Options_v.setPhaseOption("jop.cpf", "enabled:false")
		Options_v.setPhaseOption("jop.cbf", "enabled:false")
		Options_v.setPhaseOption("jop.dae", "enabled:false")
		Options_v.setPhaseOption("jop.nce", "enabled:false")
		Options_v.setPhaseOption("jop.uce1", "enabled:false")
		Options_v.setPhaseOption("jop.ubf1", "enabled:false")
		Options_v.setPhaseOption("jop.uce2", "enabled:false")
		Options_v.setPhaseOption("jop.ubf2", "enabled:false")
		Options_v.setPhaseOption("jop.ule", "enabled:false")
		Scene.v().loadNecessaryClasses()
	}

	// SootUtils.getTag(method.tags, "Llibcore/MethodBody;", "value") as String?
}

val ANNOTATIONS_BLACKLIST = listOf(
	"java.lang.annotation.Documented",
	"java.lang.Deprecated",
	"java.lang.annotation.Target",
	"java.lang.annotation.Retention",
	"kotlin.jvm.internal.KotlinLocalClass",
	"kotlin.jvm.internal.KotlinSyntheticClass",
	"kotlin.jvm.internal.KotlinClass",
	"kotlin.jvm.internal.KotlinFunction",
	"kotlin.jvm.internal.KotlinFileFacade",
	"kotlin.jvm.internal.KotlinMultifileClassPart",
	"kotlin.jvm.internal.KotlinMultifileClass",
	"kotlin.annotation.MustBeDocumented",
	"kotlin.annotation.Target",
	"kotlin.annotation.Retention",
	"kotlin.jvm.JvmStatic",
	"kotlin.Deprecated"
).map { AstType.REF(it) }.toSet()

fun Iterable<Tag>.toAstAnnotations(): List<AstAnnotation> {
	return this.filterIsInstance<VisibilityAnnotationTag>().flatMap {
		it.annotations.map {
			AstAnnotation(
				AstType.demangle(it.type) as AstType.REF,
				it.getElements().map {
					Pair(it.name, it.getValue())
				}.toMap()
			)
		}
	}.filter { it.type !in ANNOTATIONS_BLACKLIST }
}

fun AnnotationTag.getElements():List<AnnotationElem> {
	return (0 until this.numElems).map { this.getElemAt(it) }
}

fun AnnotationElem.getValue(): Any? {
	return when (this) {
		is AnnotationStringElem -> this.value
		is AnnotationBooleanElem -> this.value
		is AnnotationIntElem -> this.value
		is AnnotationFloatElem -> this.value
		is AnnotationDoubleElem -> this.value
		is AnnotationLongElem -> this.value
		is AnnotationArrayElem -> this.values.map { it.getValue() }
		is AnnotationClassElem -> null
		is AnnotationEnumElem -> null
		else -> {
			noImpl("Not implemented type: $this")
		}
	}
}

fun SootClass.getSuperClassOrNull(): SootClass? = if (this.hasSuperclass()) this.superclass else null

fun SootClass.getAncestors(includeThis: Boolean = false): List<SootClass> {
	val buffer = arrayListOf<SootClass>()
	var tclazz = if (includeThis) this else this.getSuperClassOrNull()
	while (tclazz != null) {
		buffer.add(tclazz)
		tclazz = tclazz.getSuperClassOrNull()
	}
	return buffer.toList()
}

fun SootClass.hasMethod(method: SootMethod): Boolean = this.hasMethod(method.name, method.parameterTypes as List<Type>)
fun SootClass.hasMethod(name: String, parameterTypes: List<soot.Type>): Boolean {
	/*
	return try {
		this.getMethod(name, parameterTypes) != null
	} catch (e: Throwable) {
		false
	}
	*/
	return hasMethod2(name, parameterTypes)
}

fun SootClass.hasMethod2(name: String, parameterTypes: List<soot.Type>): Boolean {
	val methodsWithName = this.getMethodsWithName(name)
	val result = methodsWithName.any {
		it.parameterTypes == parameterTypes
	}
	return result
}

fun SootClass.getMethodsWithName(name: String): List<SootMethod> {
	return this.methods.filter { it.name == name }
}

fun SootClass.getAllDirectInterfaces(): List<SootClass> {
	return if (interfaceCount == 0) {
		listOf()
	} else {
		val clazzInterfaces = interfaces.toList()
		clazzInterfaces.flatMap { clazzInterfaces + it.getAllDirectInterfaces() }
	}
}

fun SootClass.getAllDirectAndIndirectInterfaces(): List<SootClass> {
	return if (interfaceCount == 0) {
		listOf()
	} else {
		val clazzInterfaces = interfaces.toList()
		if (hasSuperclass()) {
			superclass.getAllDirectAndIndirectInterfaces() + clazzInterfaces.flatMap { clazzInterfaces + it.getAllDirectAndIndirectInterfaces() }
		} else {
			clazzInterfaces.flatMap { clazzInterfaces + it.getAllDirectAndIndirectInterfaces() }
		}
	}
}

val SootMethod.hasBody: Boolean get() = !this.isAbstract && !this.isNative
val SootMethod.isMethodOverriding: Boolean get() = this.overridingMethod != null

fun SootClass.getMethod2(name: String, parameterTypes: List<soot.Type>): SootMethod {
	return this.methods.firstOrNull {
		(it.name == name) && (it.parameterTypes == parameterTypes)
	} ?: throw RuntimeException("Class ${this.name} doesn\'t have method $name($parameterTypes)")
}

val SootMethod.overridingMethod: AstMethodRef? get() {
	val method = this
	val clazz = method.declaringClass
	val name = method.name
	val returnType = method.returnType
	val parameterTypes = method.parameterTypes as List<soot.Type>
	val ancestors = clazz.getAncestors()
	val interfaces = clazz.getAllDirectAndIndirectInterfaces()
	val overrideClass = ancestors.firstOrNull { it.hasMethod(name, parameterTypes) }
	val implementClass = interfaces.firstOrNull { it.hasMethod(name, parameterTypes) }

	//val baseClass = overrideClass ?: implementClass
	val baseClass = overrideClass

	return if (baseClass != null) {
		val overrideMethod = try {
			baseClass.getMethod(name, parameterTypes)
		} catch (e: Throwable) {
			try {
				baseClass.getMethod(name, parameterTypes, returnType)
			} catch (e: Throwable) {
				baseClass.getMethod2(name, parameterTypes)
			}
		}

		if (method.returnType.astType != overrideMethod.returnType.astType) {
			null
		} else {
			AstMethodRef(
				FqName(baseClass.name),
				overrideMethod.name,
				AstType.METHOD_TYPE(
					overrideMethod.returnType.astType,
					overrideMethod.parameterTypes.map { (it as Type).astType }
				)
			)
		}
	} else {
		null
	}
}

val SootMethod.isMethodImplementing: Boolean get() {
	fun locateMethodInInterfaces(clazz: SootClass, method: SootMethod): Boolean {
		val name = method.name
		val parameterTypes = method.parameterTypes

		if (clazz.interfaces.any { locateMethodInInterfaces(it, method) }) return true

		for (interfaze in clazz.interfaces) {
			try {
				interfaze.getMethod(name, parameterTypes)
				return true
			} catch (e: Throwable) {

			}
		}
		return false
	}
	return locateMethodInInterfaces(this.declaringClass, this)
}

fun SootClass.getAllImplementedMethods(): List<AstMethodRef> {
	return this.getAncestors(includeThis = true).flatMap { it.methods }.map { it.astRef }
}

fun SootClass.getAllMethodsToImplement(): List<AstMethodRef> {
	return this.getAllDirectAndIndirectInterfaces().flatMap { it.methods }.map { it.astRef }
}

val SootClass.isClassAbstract: Boolean get() = this.isAbstract && !this.isInterface