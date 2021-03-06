package eu.bebendorf.ajwf.router;

import eu.bebendorf.ajwf.Exchange;
import eu.bebendorf.ajwf.WebService;
import eu.bebendorf.ajwf.handler.RequestHandler;
import eu.bebendorf.ajwf.helper.HttpMethod;
import eu.bebendorf.ajwf.router.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RouteBinder {

    public static void bind(WebService service, String globalPrefix, Object controller){
        List<String> prefixes = new ArrayList<>(Arrays.stream(controller.getClass().getDeclaredAnnotationsByType(PathPrefix.class)).map(PathPrefix::value).collect(Collectors.toList()));
        if(prefixes.size() == 0)
            prefixes.add("");
        class Bind {
            final HttpMethod method;
            final String path;
            public Bind(HttpMethod method, String path){
                this.method = method;
                this.path = path;
            }
        }
        for(Method method : controller.getClass().getDeclaredMethods()){
            List<Bind> binds = new ArrayList<>();
            for(Get a : getAnnotations(Get.class, method))
                binds.add(new Bind(HttpMethod.GET, a.value()));
            for(Post a : getAnnotations(Post.class, method))
                binds.add(new Bind(HttpMethod.POST, a.value()));
            for(Put a : getAnnotations(Put.class, method))
                binds.add(new Bind(HttpMethod.PUT, a.value()));
            for(Delete a : getAnnotations(Delete.class, method))
                binds.add(new Bind(HttpMethod.DELETE, a.value()));
            if(binds.size() > 0){
                BindHandler handler = new BindHandler(service, controller, method);
                for(String prefix : prefixes){
                    for(Bind bind : binds){
                        service.route(bind.method, buildPattern(globalPrefix, prefix, bind.path), handler);
                    }
                }
            }
        }
    }

    private static String buildPattern(String globalPrefix, String prefix, String path){
        String pattern = globalPrefix != null ? globalPrefix : "";
        if(pattern.endsWith("/"))
            pattern = pattern.substring(0, pattern.length()-1);
        if(prefix.length() > 0){
            if(!prefix.startsWith("/"))
                pattern+="/";
            pattern += prefix;
            if(pattern.endsWith("/"))
                pattern = pattern.substring(0, pattern.length()-1);
        }
        if(path.length() > 0){
            if(!path.startsWith("/"))
                pattern+="/";
            pattern += path;
            if(pattern.endsWith("/"))
                pattern = pattern.substring(0, pattern.length()-1);
        }
        return pattern;
    }

    private static <T extends Annotation> List<T> getAnnotations(Class<T> type, Method method){
        return Arrays.asList(method.getDeclaredAnnotationsByType(type));
    }

    private static <T extends Annotation> T getAnnotation(Class<T> type, Method method, int param){
        if(param < 0)
            return null;
        Parameter[] parameters = method.getParameters();
        if(param >= parameters.length)
            return null;
        T[] annotations = parameters[param].getDeclaredAnnotationsByType(type);
        return annotations.length == 0 ? null : annotations[0];
    }

    private static class BindHandler implements RequestHandler {

        private final WebService service;
        private final Object controller;
        private final Method method;
        private final Object[] parameterTypes;

        public BindHandler(WebService service, Object controller, Method method){
            this.service = service;
            this.controller = controller;
            this.method = method;
            method.setAccessible(true);
            Class<?>[] types = method.getParameterTypes();
            parameterTypes = new Object[types.length];
            for(int i=0; i<parameterTypes.length; i++) {
                Attrib attrib = getAnnotation(Attrib.class, method, i);
                if(attrib != null){
                    parameterTypes[i] = attrib;
                    continue;
                }
                Query query = getAnnotation(Query.class, method, i);
                if(query != null){
                    parameterTypes[i] = query;
                    continue;
                }
                Body body = getAnnotation(Body.class, method, i);
                if(body != null){
                    parameterTypes[i] = body;
                    continue;
                }
                Path pathParam = getAnnotation(Path.class, method, i);
                if(pathParam != null){
                    parameterTypes[i] = pathParam;
                    continue;
                }
                parameterTypes[i] = types[i];
            }
        }

        public Object handle(Exchange exchange) {
            Object[] args = new Object[parameterTypes.length];
            for(int i=0; i<args.length; i++){
                if(parameterTypes[i] == null){
                    continue;
                }
                if(parameterTypes[i].equals(Exchange.class)){
                    args[i] = exchange;
                    continue;
                }
                if(parameterTypes[i].equals(HttpMethod.class)){
                    args[i] = exchange.getMethod();
                    continue;
                }
                if(parameterTypes[i] instanceof Body){
                    args[i] = exchange.getBody(method.getParameterTypes()[i]);
                    continue;
                }
                if(parameterTypes[i] instanceof Attrib){
                    Attrib attrib = (Attrib) parameterTypes[i];
                    args[i] = exchange.attrib(attrib.value());
                    continue;
                }
                if(parameterTypes[i] instanceof Query){
                    Query query = (Query) parameterTypes[i];
                    args[i] = exchange.parameters.get(query.value());
                    continue;
                }
                if(parameterTypes[i] instanceof Path){
                    Path path = (Path) parameterTypes[i];
                    args[i] = exchange.pathVariables.get(path.value());
                    continue;
                }
                if(service.getInjector() != null)
                    args[i] = service.getInjector().getInstance((Class<?>) parameterTypes[i]);
            }
            try {
                return method.invoke(controller, args);
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

}
