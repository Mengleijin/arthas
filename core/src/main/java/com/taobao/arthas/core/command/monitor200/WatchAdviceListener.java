package com.taobao.arthas.core.command.monitor200;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.advisor.AccessPoint;
import com.taobao.arthas.core.advisor.Advice;
import com.taobao.arthas.core.advisor.AdviceListenerAdapter;
import com.taobao.arthas.core.advisor.ArthasMethod;
import com.taobao.arthas.core.command.model.WatchModel;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.line.LineRange;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.ThreadLocalWatch;
import com.taobao.arthas.core.view.ObjectView;

import java.util.*;
/**
 * @author beiwei30 on 29/11/2016.
 */
class WatchAdviceListener extends AdviceListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(WatchAdviceListener.class);
    private final ThreadLocalWatch threadLocalWatch = new ThreadLocalWatch();
    private WatchCommand command;
    private CommandProcess process;

    public WatchAdviceListener(WatchCommand command, CommandProcess process, boolean verbose) {
        this.command = command;
        this.process = process;
        super.setVerbose(verbose);
    }

    private boolean isFinish() {
        return command.isFinish() || !command.isBefore() && !command.isException() && !command.isSuccess() && command.getLines().isEmpty();
    }

    @Override
    public void before(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args)
            throws Throwable {
        // 开始计算本次方法调用耗时
        threadLocalWatch.start();
        if (command.isBefore()) {
            watching(Advice.newForBefore(loader, clazz, method, target, args));
        }
    }

    @Override
    public void afterReturning(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                               Object returnObject) throws Throwable {
        Advice advice = Advice.newForAfterReturning(loader, clazz, method, target, args, returnObject);
        if (command.isSuccess()) {
            watching(advice);
        }

        finishing(advice);
    }

    @Override
    public void afterThrowing(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                              Throwable throwable) {
        Advice advice = Advice.newForAfterThrowing(loader, clazz, method, target, args, throwable);
        if (command.isException()) {
            watching(advice);
        }

        finishing(advice);
    }

    @Override
    public List<LineRange> linesToListen() {
        return command.getLines();
    }

    @Override
    public void atLine(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args, int line, String[] varNames, Object[] vars) throws Throwable {
        boolean inRange = false;
        for (LineRange lineRange : command.getLines()) {
            if (lineRange.inRange(line)) {
                inRange = true;
                break;
            }
        }

        if (!inRange) {
            return;
        }

        Advice advice = Advice.newForAtLine(loader, clazz, method, target, args, line, varNames, vars);
        watching(advice);
    }

    private void finishing(Advice advice) {
        if (isFinish()) {
            watching(advice);
        }
    }


    private void watching(Advice advice) {
        try {
            // 本次调用的耗时
            double cost = threadLocalWatch.costInMillis();
            boolean conditionResult = isConditionMet(command.getConditionExpress(), advice, cost);
            if (this.isVerbose()) {
                process.write("Condition express: " + command.getConditionExpress() + " , result: " + conditionResult + "\n");
            }
            if (conditionResult) {
                // TODO: concurrency issues for process.write
                WatchModel model = new WatchModel();
                model.setTs(new Date());
                model.setCost(cost);
                Object value = getExpressionResult(command.getExpress(), advice, cost);
                model.setValue(value);
                model.setExpand(command.getExpand());
                model.setSizeLimit(command.getSizeLimit());
                String express = command.getExpress();
                String[] split = express.split(",");
                if (advice.isBefore() && value != null && express.contains("params") && split.length != 0) {
                    parseAtEnter(model, value, split);
                } else if (advice.isAtLine() && value != null && express.contains("varMap") && split.length != 0) {
                    parseAtLine(model, value, split);
                } else if (advice.isAfterReturning() && value != null && express.contains("returnObj") && split.length != 0) {
                    parseAtExit(model, value, split);
                } else if (advice.isAfterThrowing() && value != null && express.contains("throwExp") && split.length != 0) {
                    parseAtExceptionExit(model, value, split);
                }
                model.setClassName(advice.getClazz().getName());
                model.setMethodName(advice.getMethod().getName());
                if (advice.isBefore()) {
                    model.setAccessPoint(AccessPoint.ACCESS_BEFORE.getKey());
                } else if (advice.isAfterReturning()) {
                    model.setAccessPoint(AccessPoint.ACCESS_AFTER_RETUNING.getKey());
                } else if (advice.isAfterThrowing()) {
                    model.setAccessPoint(AccessPoint.ACCESS_AFTER_THROWING.getKey());
                } else if (advice.isAtLine()) {
                    model.setAccessPoint(AccessPoint.ACCESS_AT_LINE.getKey());
                }

                process.appendResult(model);
                process.times().incrementAndGet();
                if (isLimitExceeded(command.getNumberOfLimit(), process.times().get())) {
                    WatchModel watchModel = new WatchModel();
                    watchModel.setAccessPoint("watchEnd");
                    process.appendResult(watchModel);
                    abortProcess(process, command.getNumberOfLimit());
                }
            }
        } catch (Throwable e) {
            logger.warn("watch failed.", e);
            process.end(-1, "watch failed, condition is: " + command.getConditionExpress() + ", express is: "
                    + command.getExpress() + ", " + e.getMessage() + ", visit " + LogUtil.loggingFile()
                    + " for more details.");
        }
    }

    private void parseAtExceptionExit(WatchModel model, Object value, String[] split) {
        try {
            List<Object> list = (ArrayList<Object>) value;
            if (list != null && list.size() > 0) {
                int paramsIndex = -1, throwExpIndex = -1;
                for (int i=0; i<split.length; i++) {
                    if (split[i].contains("params")) {
                        paramsIndex = i;
                    } else if (split[i].contains("throwExp")) {
                        throwExpIndex = i;
                    }
                }
                Map<String, Object> resultMap = new HashMap<String, Object>();
                if (paramsIndex != -1) {
                    Object[] objects = (Object[]) list.get(paramsIndex);
                    List<String> result = new ArrayList<String>();
                    for (Object object : objects) {
                        String paramStr = StringUtils.objectToString(
                                isNeedExpand(model) ? new ObjectView(object, model.getExpand(), model.getSizeLimit()).draw() : object);
                        result.add(paramStr);
                    }
                    resultMap.put("params", result);
                }
                if (throwExpIndex != -1) {
                    Object throwExp = list.get(throwExpIndex);
                    String throwExpStr = StringUtils.objectToString(
                            isNeedExpand(model) ? new ObjectView(throwExp, model.getExpand(), model.getSizeLimit()).draw() : throwExp);
                    resultMap.put("throwExp", throwExpStr);
                }
                model.setValue(resultMap);
            }
        } catch (Exception e) {
            logger.error("parseAtExceptionExit error, e:", e);
        }
    }

    private void parseAtExit(WatchModel model, Object value, String[] split) {
        try {
            List<Object> list = (ArrayList<Object>) value;
            if (list != null && list.size() > 0) {
                int paramsIndex = -1, returnObjIndex = -1;
                for (int i=0; i<split.length; i++) {
                    if (split[i].contains("params")) {
                        paramsIndex = i;
                    } else if (split[i].contains("returnObj")) {
                        returnObjIndex = i;
                    }
                }
                Map<String, Object> resultMap = new HashMap<String, Object>();
                if (paramsIndex != -1) {
                    Object[] objects = (Object[]) list.get(paramsIndex);
                    List<String> result = new ArrayList<String>();
                    for (Object object : objects) {
                        String paramStr = StringUtils.objectToString(
                                isNeedExpand(model) ? new ObjectView(object, model.getExpand(), model.getSizeLimit()).draw() : object);
                        result.add(paramStr);
                    }
                    resultMap.put("params", result);
                }
                if (returnObjIndex != -1) {
                    Object returnObj = list.get(returnObjIndex);

                    String returnObjStr = StringUtils.objectToString(
                            isNeedExpand(model) ? new ObjectView(returnObj, model.getExpand(), model.getSizeLimit()).draw() : returnObj);
                    resultMap.put("returnObj", returnObjStr);
                }
                model.setValue(resultMap);
            }
        } catch (Exception e) {
            logger.error("parseAtExit error, e:", e);
        }
    }

    private void parseAtLine(WatchModel model, Object value, String[] split) {
        try {
            List<Object> list = (ArrayList<Object>) value;
            if (list != null && list.size() > 0) {
                int varMapIndex = -1;
                for (int i=0; i<split.length; i++) {
                    if (split[i].contains("varMap")) {
                        varMapIndex = i;
                        break;
                    }
                }
                if (varMapIndex == -1) return;
                LinkedHashMap<String, Object> variables = (LinkedHashMap<String, Object>) list.get(varMapIndex);
                Map<String, String> resultMap = new HashMap<String, String>();
                for (String variableName : variables.keySet()) {
                    if ("this".equals(variableName)) {
                        continue;
                    }
                    Object object = variables.get(variableName);
                    String result = StringUtils.objectToString(
                            isNeedExpand(model) ? new ObjectView(object, model.getExpand(), model.getSizeLimit()).draw() : object);
                    resultMap.put(variableName, result);
                }
                model.setValue(resultMap);
            }
        } catch (Exception e) {
            logger.error("parseAtLine error, e:", e);
        }
    }

    private void parseAtEnter(WatchModel model, Object value, String[] split) {
        try {
            List<Object> list = (ArrayList<Object>) value;
            if (list != null && list.size() > 0) {
                int paramsIndex = -1;
                for (int i=0; i<split.length; i++) {
                    if (split[i].contains("params")) {
                        paramsIndex = i;
                        break;
                    }
                }
                if (paramsIndex == -1) return;
                Object[] objects = (Object[]) list.get(paramsIndex);
                List<String> result = new ArrayList<String>();
                for (Object object : objects) {
                    String paramStr = StringUtils.objectToString(
                                isNeedExpand(model) ? new ObjectView(object, model.getExpand(), model.getSizeLimit()).draw() : object);
                    result.add(paramStr);
                }
//                Map<String, Object> map = new HashMap<String, Object>();
//                for (Object object : objects) {
//                    String name = object.getClass().getName();
//                    if (isPrimitive(object)) {
//                        map.put(name, object);
//                    } else {
////                        //深拷贝
////                        ObjectMapper objectMapper = new ObjectMapper();
////                        Object objectCopy = objectMapper.readValue(objectMapper.writeValueAsString(value), Object.class);
//                        String result = StringUtils.objectToString(
//                                isNeedExpand(model) ? new ObjectView(object, model.getExpand(), model.getSizeLimit()).draw() : object);
//                        map.put(name, result);
//                    }
//                }
                model.setValue(result);
            }
        } catch (Exception e) {
            logger.error("parseAtEnter error, e:", e);
        }
    }

    private boolean isNeedExpand(WatchModel model) {
        Integer expand = model.getExpand();
        return null != expand && expand >= 0;
    }

    public boolean isPrimitive(Object obj) {
        // 基础类型
        if (Integer.class.isInstance(obj)
                || Long.class.isInstance(obj)
                || Float.class.isInstance(obj)
                || Double.class.isInstance(obj)
                || Character.class.isInstance(obj)
                || Short.class.isInstance(obj)
                || Byte.class.isInstance(obj)
                || Boolean.class.isInstance(obj)) {
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }
}
