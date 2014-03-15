package no.bekk.boss.bpep.generator;

import static no.bekk.boss.bpep.resolver.Resolver.getName;
import static no.bekk.boss.bpep.resolver.Resolver.getType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.NamingConventions;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

public class BuilderGenerator implements Generator {

	private static final String BUILDER_METHOD_PARAMETER_SUFFIX = "Param";

	private final boolean createBuilderConstructor;
	private final boolean createStaticWithMethods;
	private final boolean createBuilderGetters;
	private final boolean createClassGetters;
	private final boolean createClassSetters;
	private final boolean createCopyConstructor;
	private final boolean formatSource;

	public void generate(ICompilationUnit cu, List<IField> fields) {

		try {
			removeOldClassConstructor(cu);
			removeOldBuilderClass(cu);

			IBuffer buffer = cu.getBuffer();

			IType clazz = cu.getTypes()[0];
			final BuilderPrinter printer = new BuilderPrinter(clazz, fields);

			if(!createBuilderConstructor) {
				printer.createClassConstructor();
			}

			if(createStaticWithMethods) {
				printer.createStaticWithMethods(createCopyConstructor);
			}

			if(createClassGetters) {
				printer.createGetters();
			}
			if(createClassSetters) {
				printer.createClassSetters();
			}

			printer.printBuilderHead();
			printer.createFieldDeclarations();

			if (createCopyConstructor) {
				printer.createCopyConstructor();
			}

			if (createBuilderConstructor) {
				printer.createPrivateBuilderConstructor();
			} else {
				printer.createClassBuilderConstructor();
			}

			printer.createBuilderMethods();
			if (createBuilderGetters) {
				printer.createGetters();
			}
			
			printer.printBuilderTail();

			int pos = insertPos(clazz);

			if (formatSource) {
				buffer.replace(pos, 0, printer.toString());
				String builderSource = buffer.getContents();

				final TextEdit text = formatCode(builderSource);
				// text is null if source cannot be formatted
				if (text != null) {
					Document simpleDocument = new Document(builderSource);
					text.apply(simpleDocument);
					buffer.setContents(simpleDocument.get());
				} 
			} else {
				buffer.replace(pos, 0, printer.toString());
			}
		} catch (JavaModelException e) {
			e.printStackTrace();
		} catch (MalformedTreeException e) {
			e.printStackTrace();
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	private TextEdit formatCode(String builderSource) {
		final CodeFormatter formatter = ToolFactory.createCodeFormatter(null);
		return formatter.format(CodeFormatter.K_COMPILATION_UNIT, builderSource, 0, builderSource.length(), 0, "\n");
	}

	private int insertPos(final IType clazz)
	throws JavaModelException {
		final String clazzText = clazz.getSource();
		int pos = clazzText.lastIndexOf('}');
		if (pos < 0) {
			pos = clazzText.length();
		}
		pos += clazz.getSourceRange().getOffset();
		return pos;
	}

	private void removeOldBuilderClass(ICompilationUnit cu) throws JavaModelException {
		for (IType type : cu.getTypes()[0].getTypes()) {
			if (type.getElementName().equals("Builder") && type.isClass()) {
				type.delete(true, null);
				break;
			}
		}
	}

	private void removeOldClassConstructor(ICompilationUnit cu) throws JavaModelException {
		for (IMethod method : cu.getTypes()[0].getMethods()) {
			if (method.isConstructor() && method.getParameterTypes().length == 1 && method.getParameterTypes()[0].equals("QBuilder;")) {
				method.delete(true, null);
				break;
			}
		}
	}


	private static class BuilderPrinter {
		private final StringWriter sw;
		private final PrintWriter pw;
		private final IType clazz;
		private final List<IField> fields;
		private IJavaProject javaProject = null;

		BuilderPrinter(IType clazz, List<IField> fields) {
			this.clazz = clazz;
			this.fields = fields;
			sw = new StringWriter();
			pw = new PrintWriter(sw);
		}

		@Override
		public String toString() {
			return sw.toString();
		}

		void println() {
			pw.println();
		}

		void println(final String line) {
			pw.println(line);
		}

		void printBuilderHead() {
			println();
			println("public static class Builder {");
		}

		void printBuilderTail() {
			println("}");
		}

		public void createGetters() {
			for (IField field : fields) {
				final String fieldName = getName(field);
				final String fieldType = getType(field);
				final String getterName = getterName(field);
				println("public " + fieldType + " " + getterName + "() {");
				println("return " + fieldName + ";");
				println("}");
			}
		}

		public void createClassSetters() {
			for (IField field : fields) {
				final String fieldName = getName(field);
				final String fieldType = getType(field);
				final String setterName = setterName(field);
				final String baseName = getFieldBaseName(fieldName);
				println("public void " + setterName + "(final " + fieldType + " " + baseName + ") {");
				println("this." + fieldName + "=" + baseName + ";");
				println("}");
			}
		}

		void createCopyConstructor() {
			String clazzName = clazzName();
			println("public Builder(){}");
			println("public Builder(final " + clazzName + " original){");
			printFieldAssignments("this", "original");
			println("}");
		}

		void createClassConstructor() throws JavaModelException {
			println("private " + clazzName() + "(final Builder builder){");
			printFieldAssignments("this", "builder");
			println("}");
		}

		public void createStaticWithMethods(final boolean hasCopyContructor) {
			println("public static Builder with() {");
			println("    return new Builder();");
			println("}");
			if (hasCopyContructor) {
				println("public static Builder with(final " + clazzName() + " original) {");
				println("    return new Builder(original);");
				println("}");
			}
		}

		void createClassBuilderConstructor() {
			String clazzName = clazzName();
			println("public " + clazzName + " build(){");
			println("return new " + clazzName + "(this);");
			println("}");
		}

		void createPrivateBuilderConstructor() {
			final String clazzName = clazzName();
			final String clazzVariable = clazzName.substring(0, 1).toLowerCase() + clazzName.substring(1);
			println("public " + clazzName + " build(){");
			pw.println(clazzName + " " + clazzVariable + "=new " + clazzName + "();");
			printFieldAssignments(clazzVariable, null);
			println("return " + clazzVariable + ";");
			println("}");
		}

		void createBuilderMethods() throws JavaModelException {
			for (IField field : fields) {
				final String fieldName = getName(field);
				final String fieldType = getType(field);
				final String baseName = getFieldBaseName(fieldName);
				final String parameterName = baseName + BUILDER_METHOD_PARAMETER_SUFFIX;
				println("public Builder " + baseName + "(" + fieldType + " " + parameterName + ") {");
				println("this." + fieldName + "=" + parameterName + ";");
				println("return this;");
				println("}");
			}
		}

		private String getFieldBaseName(String fieldName) {
			return NamingConventions.getBaseName(NamingConventions.VK_INSTANCE_FIELD, fieldName, javaProject());
		}

		private IJavaProject javaProject() {
			if (null == javaProject) {
				javaProject = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot().getProject());
			}
			return javaProject;
		}

		private String getterName(final IField field) {
			return NamingConventions.suggestGetterName(javaProject(), getName(field), 0, isBooleanField(field), null);
		}

		private String setterName(final IField field) {
			return NamingConventions.suggestSetterName(javaProject(), getName(field), 0, isBooleanField(field), null);
		}

		private boolean isBooleanField(final IField field) {
			try {
				return "boolean".equals(getType(field).trim());
			}
			catch (final Exception e) {
				return false;
			}
		}

		private void createFieldDeclarations() throws JavaModelException {
			for (IField field : fields) {
				println(getType(field) + " " + getName(field) + ";");
			}
		}

		private String clazzName() {
			return clazz.getElementName();
		}

		private void printFieldAssignments(final String targetName, final String sourceName) {
			final String targetFrag = targetName + ".";
			final String sourceFrag = (null == sourceName) ? "=" : ("=" + sourceName + ".");
			for (IField f : fields) {
				final String fName = getName(f);
				println(targetFrag + fName + sourceFrag + fName + ";");
			}
		}
	}


	public static class BuilderGeneratorOptions {
		boolean createBuilderConstructor;
		boolean createStaticWithMethods;
		boolean createCopyConstructor;
		boolean formatSource;
		boolean createBuilderGetters;
		boolean createClassGetters;
		boolean createClassSetters;

		public BuilderGeneratorOptions() {
			// And these are the default options!
			createBuilderConstructor = false;
			createStaticWithMethods = true;
			createCopyConstructor = true;
			createBuilderGetters = false;
			createClassGetters = false;
			createClassSetters = false;
			formatSource = true;
		}

		public boolean isCreateBuilderConstructor() {
			return createBuilderConstructor;
		}

		public boolean isCreateStaticWithMethods() {
			return createStaticWithMethods;
		}

		public boolean isCreateCopyConstructor() {
			return createCopyConstructor;
		}

		public boolean isCreateBuilderGetters() {
			return createBuilderGetters;
		}

		public boolean isCreateClassGetters() {
			return createClassGetters;
		}

		public boolean isCreateClassSetters() {
			return createClassSetters;
		}

		public boolean isFormatSource() {
			return formatSource;
		}

		public BuilderGeneratorOptions createBuilderConstructor(final boolean createBuilderConstructorParam) {
			this.createBuilderConstructor = createBuilderConstructorParam;
			return this;
		}

		public BuilderGeneratorOptions createStaticWithMethods(final boolean createStaticWithMethodParam) {
			this.createStaticWithMethods = createStaticWithMethodParam;
			return this;
		}

		public BuilderGeneratorOptions createCopyConstructor(final boolean createCopyConstructorParam) {
			this.createCopyConstructor = createCopyConstructorParam;
			return this;
		}

		public BuilderGeneratorOptions createBuilderGetters(final boolean createBuilderGettersParam) {
			this.createBuilderGetters = createBuilderGettersParam;
			return this;
		}

		public BuilderGeneratorOptions createClassGetters(final boolean createClassGettersParam) {
			this.createClassGetters = createClassGettersParam;
			return this;
		}

		public BuilderGeneratorOptions createClassSetters(final boolean createClassSettersParam) {
			this.createClassSetters = createClassSettersParam;
			return this;
		}

		public BuilderGeneratorOptions formatSource(boolean formatSourceParam) {
			this.formatSource = formatSourceParam;
			return this;
		}

		public BuilderGenerator build() {
			return new BuilderGenerator(this);
		}
	}

	BuilderGenerator(BuilderGeneratorOptions builder) {
		this.createBuilderConstructor = builder.createBuilderConstructor;
		this.createStaticWithMethods = builder.createStaticWithMethods;
		this.createCopyConstructor = builder.createCopyConstructor;
		this.createBuilderGetters = builder.createBuilderGetters;
		this.createClassGetters   = builder.createClassGetters;
		this.createClassSetters   = builder.createClassSetters;
		this.formatSource = builder.formatSource;
	}
}
