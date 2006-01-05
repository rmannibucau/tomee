package org.openejb.core.stateless;

import java.lang.reflect.Method;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.Properties;

import javax.ejb.EJBHome;
import javax.ejb.EJBLocalHome;
import javax.ejb.EJBLocalObject;
import javax.ejb.EJBObject;
import javax.ejb.EnterpriseBean;
import javax.ejb.SessionBean;
import javax.transaction.TransactionManager;

import org.openejb.Container;
import org.openejb.DeploymentInfo;
import org.openejb.OpenEJB;
import org.openejb.OpenEJBException;
import org.openejb.ProxyInfo;
import org.openejb.ClassLoaderUtil;
import org.openejb.spi.SecurityService;
import org.openejb.core.EnvProps;
import org.openejb.core.Operations;
import org.openejb.core.ThreadContext;
import org.openejb.core.transaction.TransactionContainer;
import org.openejb.core.transaction.TransactionContext;
import org.openejb.core.transaction.TransactionPolicy;
import org.openejb.util.SafeProperties;
import org.openejb.util.SafeToolkit;

public class StatelessContainer implements org.openejb.RpcContainer, TransactionContainer {

    StatelessInstanceManager instanceManager;

    HashMap deploymentRegistry;

    Object containerID = null;
    private TransactionManager transactionManager;

    public void init(Object id, HashMap registry, Properties properties) throws org.openejb.OpenEJBException {
        transactionManager = (TransactionManager) properties.get("TransactionManager");
        containerID = id;
        deploymentRegistry = registry;

        if (properties == null) properties = new Properties();

        SafeToolkit toolkit = SafeToolkit.getToolkit("StatelessContainer");
        SafeProperties safeProps = toolkit.getSafeProperties(properties);
        try {
            String className = safeProps.getProperty(EnvProps.IM_CLASS_NAME, "org.openejb.core.stateless.StatelessInstanceManager");
            ClassLoader cl = ClassLoaderUtil.getContextClassLoader();
            instanceManager = (StatelessInstanceManager) Class.forName(className, true, cl).newInstance();
        } catch (Exception e) {
            throw new org.openejb.SystemException("Initialization of InstanceManager for the \"" + containerID + "\" stateful container failed", e);
        }
        instanceManager.init(properties);

//        txScopeHandle = new StatelessTransactionScopeHandler(this,instanceManager);

        /*
        * This block of code is necessary to avoid a chicken and egg problem. The DeploymentInfo
        * objects must have a reference to their container during this assembly process, but the
        * container is created after the DeploymentInfo necessitating this loop to assign all
        * deployment info object's their containers.
        */
        org.openejb.DeploymentInfo [] deploys = this.deployments();
        for (int x = 0; x < deploys.length; x++) {
            org.openejb.core.DeploymentInfo di = (org.openejb.core.DeploymentInfo) deploys[x];
            di.setContainer(this);
        }

    }

    public DeploymentInfo [] deployments() {
        return (DeploymentInfo []) deploymentRegistry.values().toArray(new DeploymentInfo[deploymentRegistry.size()]);
    }

    public DeploymentInfo getDeploymentInfo(Object deploymentID) {
        return (DeploymentInfo) deploymentRegistry.get(deploymentID);
    }

    public int getContainerType() {
        return Container.STATELESS;
    }

    public Object getContainerID() {
        return containerID;
    }

    public void deploy(Object deploymentID, DeploymentInfo info) throws OpenEJBException {
        HashMap registry = (HashMap) deploymentRegistry.clone();
        registry.put(deploymentID, info);
        deploymentRegistry = registry;
    }

    public Object invoke(Object deployID, Method callMethod, Object [] args, Object primKey, Object securityIdentity)
            throws org.openejb.OpenEJBException {
        try {

            org.openejb.core.DeploymentInfo deployInfo = (org.openejb.core.DeploymentInfo) this.getDeploymentInfo(deployID);

            ThreadContext callContext = ThreadContext.getThreadContext();
            callContext.set(deployInfo, primKey, securityIdentity);

            boolean authorized = getSecurityService().isCallerAuthorized(securityIdentity, deployInfo.getAuthorizedRoles(callMethod));
            if (!authorized)
                throw new org.openejb.ApplicationException(new RemoteException("Unauthorized Access by Principal Denied"));

            Class declaringClass = callMethod.getDeclaringClass();
            if (EJBHome.class.isAssignableFrom(declaringClass) || EJBLocalHome.class.isAssignableFrom(declaringClass)) {
                if (callMethod.getName().equals("create")) {
                    return createEJBObject(deployInfo, callMethod);
                } else
                    return null;// EJBHome.remove( ) and other EJBHome methods are not process by the container
            } else if (EJBObject.class == declaringClass || EJBLocalObject.class == declaringClass) {
                return null;// EJBObject.remove( ) and other EJBObject methods are not process by the container
            }

            SessionBean bean = null;

            bean = (SessionBean) instanceManager.getInstance(callContext);

            callContext.setCurrentOperation(Operations.OP_BUSINESS);

            Method runMethod = deployInfo.getMatchingBeanMethod(callMethod);

            Object retValue = invoke(callMethod, runMethod, args, bean, callContext);
            instanceManager.poolInstance(callContext, bean);

            return deployInfo.convertIfLocalReference(callMethod, retValue);

        } finally {
            /*
                The thread context must be stripped from the thread before returning or throwing an exception
                so that an object outside the container does not have access to a
                bean's JNDI ENC.  In addition, its important for the
                org.openejb.core.ivm.java.javaURLContextFactory, which determines the context
                of a JNDI lookup based on the presence of a ThreadContext object.  If no ThreadContext
                object is available, then the request is assumed to be made from outside the container
                system and is given the global OpenEJB JNDI name space instead.  If there is a thread context,
                then the request is assumed to be made from within the container system and so the
                javaContextFactory must return the JNDI ENC of the current enterprise bean which it
                obtains from the DeploymentInfo object associated with the current thread context.
            */
            ThreadContext.setThreadContext(null);
        }
    }

    private SecurityService getSecurityService() {
        return OpenEJB.getSecurityService();
    }

    public StatelessInstanceManager getInstanceManager() {
        return instanceManager;
    }

    protected Object invoke(Method callMethod, Method runMethod, Object [] args, EnterpriseBean bean, ThreadContext callContext)
            throws org.openejb.OpenEJBException {

        TransactionPolicy txPolicy = callContext.getDeploymentInfo().getTransactionPolicy(callMethod);
        TransactionContext txContext = new TransactionContext(callContext, getTransactionManager());
        txContext.callContext = callContext;

        txPolicy.beforeInvoke(bean, txContext);

        Object returnValue = null;
        try {

            returnValue = runMethod.invoke(bean, args);
        } catch (java.lang.reflect.InvocationTargetException ite) {// handle exceptions thrown by enterprise bean
            if (ite.getTargetException() instanceof RuntimeException) {
                /* System Exception ****************************/

                txPolicy.handleSystemException(ite.getTargetException(), bean, txContext);
            } else {
                /* Application Exception ***********************/
                instanceManager.poolInstance(callContext, bean);

                txPolicy.handleApplicationException(ite.getTargetException(), txContext);
            }
        } catch (Throwable re) {// handle reflection exception
            /*
              Any exception thrown by reflection; not by the enterprise bean. Possible
              Exceptions are:
                IllegalAccessException - if the underlying method is inaccessible.
                IllegalArgumentException - if the number of actual and formal parameters differ, or if an unwrapping conversion fails.
                NullPointerException - if the specified object is null and the method is an instance method.
                ExceptionInInitializerError - if the initialization provoked by this method fails.
            */
            txPolicy.handleSystemException(re, bean, txContext);
        } finally {

            txPolicy.afterInvoke(bean, txContext);
        }

        return returnValue;
    }

    private TransactionManager getTransactionManager() {
        return transactionManager;
    }

    protected ProxyInfo createEJBObject(org.openejb.core.DeploymentInfo deploymentInfo, Method callMethod) {
        Class callingClass = callMethod.getDeclaringClass();
        boolean isLocalInterface = EJBLocalHome.class.isAssignableFrom(callingClass);
        return new ProxyInfo(deploymentInfo, null, isLocalInterface, this);
    }

    public void discardInstance(EnterpriseBean instance, ThreadContext context) {
        instanceManager.discardInstance(context, instance);
    }

}
