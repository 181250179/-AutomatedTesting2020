import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.ShrikeBTMethod;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.annotations.Annotation;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.FileProvider;

import java.io.*;
import java.util.*;

class WalaInfo{
    private String command;
    private String project_target;
    private String change_info;
    public Map<String, Set<String>> classMap = new HashMap<>();
    public Map<String, Set<String>> methodMap = new HashMap<>();
    public Map<String, Set<String>> classMethodMap = new HashMap<>();
    public Map<String, ShrikeBTMethod> BTMethodMap = new HashMap<>();
    public Set<String> temp = new HashSet<>();
    public WalaInfo(String[] str){
        this.command=str[0];
        this.project_target=str[1];
        this.change_info=str[2];
    }
    public String getCommand(){
        return this.command;
    }
    public String getProject_target(){
        return this.project_target;
    }
    public String getChange_info(){
        return this.change_info;
    }
}

public class test {
    public static void main(String[]args) throws CancelException, ClassHierarchyException, InvalidClassFileException, IOException {
        WalaInfo walaInfo=new WalaInfo(args);//初始化命令

        CallGraph graph=creatGraph(walaInfo);//使用0-CFA算法构建图

        nodeAnalysis(graph,walaInfo);//对节点进行分析并把相应的信息记录到walainfo里

        //creatDot(walaInfo);//根据记录的信息生成.dot文件

        selection(walaInfo);//做变更选择

        //test();//测试选择结果
    }
    public static CallGraph creatGraph(WalaInfo walaInfo) throws IOException, InvalidClassFileException, ClassHierarchyException, CancelException {
        CallGraph cg;
        File exFile = new FileProvider().getFile("exclusion.txt");

        AnalysisScope scope = AnalysisScopeReader.readJavaScope("scope.txt", exFile, ClassLoader.getSystemClassLoader());

        List<File> fileList = new ArrayList<>();
        loadFiles(walaInfo.getProject_target(), fileList);

        for (int i = 0; i < fileList.size(); i++) {
            scope.addClassFileToScope(ClassLoaderReference.Application, fileList.get(i));
        }

        // 生成类层次关系对象
        ClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);
        // 生成进入点,使用0-CFA算法
        AllApplicationEntrypoints entrypoints = new AllApplicationEntrypoints(scope, cha);
        AnalysisOptions option = new AnalysisOptions(scope, entrypoints);
        SSAPropagationCallGraphBuilder builder = Util.makeZeroCFABuilder(
                Language.JAVA, option, new AnalysisCacheImpl(), cha, scope);

        cg = builder.makeCallGraph(option);
        return cg;
    }
    public static void nodeAnalysis(CallGraph graph, WalaInfo walaInfo){
        for (CGNode node : graph) {

            // node中包含了很多信息，包括类加载器、方法信息等，这里只筛选出需要的信息
            if (node.getMethod() instanceof ShrikeBTMethod) {
                //node.getMethod()返回一个比较泛化的IMethod实例，不能获取到我们想要的信息
                //一般地，本项目中所有和业务逻辑相关的方法都是ShrikeBTMethod对象
                ShrikeBTMethod method = (ShrikeBTMethod) node.getMethod();
                // 使用Primordial类加载器加载的类都属于Java原生类，我们一般不关心。
                if ("Application".equals(method.getDeclaringClass().getClassLoader().toString())) {

                    // 获取声明该方法的类的内部表示
                    String classInnerName = method.getDeclaringClass().getName().toString();
                    walaInfo.classMap.putIfAbsent(classInnerName, new HashSet<>());
                    walaInfo.classMethodMap.putIfAbsent(classInnerName, new HashSet<>());

                    // 获取方法签名
                    String signature = method.getSignature();
                    String signature_class = classInnerName + " " + signature;
                    //存储信息
                    walaInfo.methodMap.putIfAbsent(signature_class, new HashSet<>());
                    walaInfo.BTMethodMap.putIfAbsent(signature_class, method);
                    walaInfo.classMethodMap.get(classInnerName).add(classInnerName + " " + signature);
                    System.out.println(classInnerName + " " + signature);

                    Iterator<CGNode> preItems = graph.getPredNodes(node);
                    while (preItems.hasNext()) {
                        CGNode preNode = preItems.next();
                        if (preNode.getMethod() instanceof ShrikeBTMethod) {
                            ShrikeBTMethod preMethod = (ShrikeBTMethod) preNode.getMethod();
                            // 使用Primordial类加载器加载的类都属于Java原生类，我们一般不关心。
                            if ("Application".equals(preMethod.getDeclaringClass().getClassLoader().toString())) {

                                // 获取声明该方法的类的内部表示
                                String preClass = preMethod.getDeclaringClass().getName().toString();
                                walaInfo.classMap.get(classInnerName).add(preClass);
                                // 获取方法签名
                                String preSignature = preMethod.getSignature();
                                Collection<Annotation> annotations = preMethod.getAnnotations();
                                walaInfo.methodMap.get(signature_class).add(preClass + " " + preSignature);
                                System.out.println(preClass + " " + preSignature);
                                for (Annotation a : annotations) {
                                    System.out.println(a.getType());
                                }

                            }
                        }
                    }
                }
            } else {
                System.out.println(String.format("'%s'不是一个ShrikeBTMethod：%s", node.getMethod(), node.getMethod().getClass()));
            }
        }
        //remove  Empty项
        walaInfo.classMap.entrySet().removeIf(item -> item.getValue().isEmpty());
        walaInfo.methodMap.entrySet().removeIf(item -> item.getValue().isEmpty());
    }
    public static void creatDot(WalaInfo walaInfo){
        try {
            BufferedWriter Cwriter = new BufferedWriter(new FileWriter("class.dot"));
            String classTitle = "digraph cmd_class {\n";
            Cwriter.write(classTitle);
            for (Map.Entry<String, Set<String>> entry : walaInfo.classMap.entrySet()) {
                for (String str : entry.getValue()) {
                    String classLine = "   \"" + entry.getKey() + "\" -> \"" + str + "\";\n";
                    Cwriter.write(classLine);
                }
            }
            Cwriter.write("}");
            Cwriter.close();
            BufferedWriter Mwriter = new BufferedWriter(new FileWriter("method.dot"));
            String methodTitle = "digraph cmd_method {\n";
            Mwriter.write(methodTitle);
            for (Map.Entry<String, Set<String>> entry : walaInfo.methodMap.entrySet()) {
                String key = entry.getKey().split(" ")[1];
                for (String str : entry.getValue()) {

                    String value = str.split(" ")[1];
                    String methodLine = "   \"" + key + "\" -> \"" + value + "\";\n";
                    Mwriter.write(methodLine);
                }
            }
            Mwriter.write("}");
            Mwriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static void selection(WalaInfo walaInfo){
        String command=walaInfo.getCommand();
        if(command.equals("-c")){
            Set<String> CChange = new HashSet<>();//存储class的change
            Set<String> MChange = new HashSet<>();//存储method的change

            try {//读取变更信息
                BufferedReader in = new BufferedReader(new FileReader(walaInfo.getChange_info()));
                String str;
                while ((str = in.readLine()) != null) {
                    String[] strings = str.split(" ");
                    CChange.add(strings[0]);
                    MChange.add(str);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            //类选择
            try {
                BufferedWriter Cwriter = new BufferedWriter(new FileWriter("./selection-class.txt"));
                Set<String> methods = new HashSet<>();
                walaInfo.temp =new HashSet<>();
                for (String className : CChange) {
                    for (String classMethod : walaInfo.classMethodMap.get(className)) {
                        recurPre(classMethod, methods,walaInfo);
                    }
                }
                for (String method : methods) {
                    Cwriter.write(method + "\n");
                }
                Cwriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        else if(command.equals("-m")){
            Set<String> MChange = new HashSet<>();

            try {
                BufferedReader in = new BufferedReader(new FileReader(walaInfo.getChange_info()));
                String str;
                while ((str = in.readLine()) != null) {

                    MChange.add(str);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            //方法选择
            try {

                BufferedWriter Mwriter = new BufferedWriter(new FileWriter("./selection-method.txt"));
                Set<String> methods = new HashSet<>();
                walaInfo.temp =new HashSet<>();
                for (String methodName : MChange) {

                    recurPre(methodName, methods,walaInfo);
                }
                for (String method : methods) {
                    Mwriter.write(method + "\n");
                }
                Mwriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public static void loadFiles(String path, List<File> fileList) {

        File dir = new File(path);
        File[] files = dir.listFiles(); // 该文件目录下文件全部放入数组
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                //是文件夹的话就是要递归再深入查找文件
                if (files[i].isDirectory()) { // 判断是文件还是文件夹
                    loadFiles(files[i].getAbsolutePath(), fileList); // 获取文件绝对路径
                } else {
                    //如果是文件，找到.class文件
                    if (files[i].getName().endsWith(".class"))
                        fileList.add(files[i]);
                }
            }
        }
    }
    public static void recurPre(String method, Set<String> methods, WalaInfo walaInfo) {
        if (!walaInfo.methodMap.containsKey(method)) {
            return;
        }
        Set<String> methodNames=walaInfo.methodMap.get(method);

        for(String methodName:methodNames){

            if(walaInfo.methodMap.containsKey(methodName)){
                if(!walaInfo.temp.contains(methodName)){
                    walaInfo.temp.add(methodName);
                    recurPre(methodName,methods,walaInfo);
                }
            }
            else {
                boolean flag=false;
                Collection<Annotation> annotations=walaInfo.BTMethodMap.get(methodName).getAnnotations();
                for(Annotation annotation:annotations){
                    if(annotation.getType().toString().equals("<Application,Lorg/junit/Test>")){
                        flag=true;
                        break;
                    }
                }
                if(walaInfo.BTMethodMap.get(methodName).isInit()||walaInfo.BTMethodMap.get(methodName).isClinit()){
                    flag=false;
                }
                if(flag){
                    methods.add(methodName);
                }

            }
        }
    }
    public static void test(){
        try{
            BufferedReader in2 = new BufferedReader(new FileReader("C:\\Users\\无名神祗\\Desktop\\自动化测试\\大作业\\Data更正-20201116\\1-ALU\\selection-class.txt"));
            BufferedReader in1 = new BufferedReader(new FileReader("./selection-class.txt"));
            String str;
            ArrayList<String> s1=new ArrayList<>();
            while ((str = in1.readLine()) != null) {
                //System.out.println(str);
                if(str.length()<5)
                    continue;
                s1.add(str);
            }
            ArrayList<String> s2=new ArrayList<>();
            while ((str = in2.readLine()) != null) {
                //System.out.println(str);
                if(str.length()<5)
                    continue;
                s2.add(str);
            }

            int cnt1=0;
            for(int i=0;i<s1.size();i++){
                str=s1.get(i);
                for(int j=0;j<s2.size();j++){
                    if(str.equals(s2.get(j)))
                        break;
                    if(j==s2.size() - 1)
                        cnt1++;
                }
            }
            int cnt2=0;
            for(int i=0;i<s2.size();i++){
                str=s2.get(i);
                for(int j=0;j<s1.size();j++){
                    if(str.equals(s1.get(j)))
                        break;
                    if(j==s1.size() - 1)
                        cnt2++;
                }
            }
            System.out.println(cnt1);
            System.out.println(cnt2);
        }catch (IOException e){
            System.out.println("erro");
       }

    }


}
