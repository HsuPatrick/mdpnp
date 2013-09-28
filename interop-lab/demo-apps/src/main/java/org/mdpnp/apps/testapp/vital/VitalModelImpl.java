package org.mdpnp.apps.testapp.vital;

import ice.AlarmSettingsObjectiveDataWriter;
import ice.DeviceConnectivity;
import ice.DeviceIdentity;
import ice.Numeric;
import ice.NumericDataReader;
import ice.NumericSeq;
import ice.NumericTopic;
import ice.NumericTypeSupport;

import java.awt.Color;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.jfree.util.Log;
import org.mdpnp.apps.testapp.Device;
import org.mdpnp.apps.testapp.DeviceIcon;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.devices.EventLoop;
import org.mdpnp.devices.EventLoopHandler;
import org.mdpnp.devices.TopicUtil;
import org.mdpnp.rti.dds.DDS;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.infrastructure.Condition;
import com.rti.dds.infrastructure.RETCODE_NO_DATA;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.infrastructure.StringSeq;
import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.InstanceStateKind;
import com.rti.dds.subscription.QueryCondition;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.SampleInfoSeq;
import com.rti.dds.subscription.SampleStateKind;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.ViewStateKind;
import com.rti.dds.topic.Topic;
import com.rti.dds.topic.TopicDescription;

public class VitalModelImpl implements VitalModel {

    private final List<Vital> vitals = Collections.synchronizedList(new ArrayList<Vital>());

    private VitalModelListener[] listeners = new VitalModelListener[0];

    protected NumericDataReader numericReader;
    private final Map<Vital, Set<QueryCondition>> queryConditions = new HashMap<Vital, Set<QueryCondition>>();

    protected Subscriber subscriber;
    protected EventLoop eventLoop;
    private State state = State.Normal;

    private final EventLoop.ConditionHandler numericHandler = new EventLoop.ConditionHandler() {
        private final NumericSeq num_seq = new NumericSeq();
        private final SampleInfoSeq info_seq = new SampleInfoSeq();

        @Override
        public void conditionChanged(Condition condition) {
            try {
                for (;;) {
                    try {
                        numericReader.read_w_condition(num_seq, info_seq, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                                (QueryCondition) condition);
                        for (int i = 0; i < info_seq.size(); i++) {
                            SampleInfo sampleInfo = (SampleInfo) info_seq.get(i);
                            if (0 != (sampleInfo.instance_state & InstanceStateKind.NOT_ALIVE_INSTANCE_STATE)) {
                                Numeric keyHolder = new Numeric();
                                numericReader.get_key_value(keyHolder, sampleInfo.instance_handle);
                                Log.debug("Numeric NOT ALIVE:"+keyHolder);
                                removeNumeric(keyHolder.universal_device_identifier, keyHolder.name);
                            } else {
                                if (sampleInfo.valid_data) {
                                    Numeric n = (Numeric) num_seq.get(i);
                                    updateNumeric(n, sampleInfo);
                                }
                            }
                        }
                    } finally {
                        numericReader.return_loan(num_seq, info_seq);
                    }
                }
            } catch (RETCODE_NO_DATA noData) {

            } finally {

            }
        }
    };

    protected void removeNumeric(String udi, int name) {
        Vital[] vitals = vitalBuffer.get();
        vitals = this.vitals.toArray(vitals);
        vitalBuffer.set(vitals);
        for (Vital v : vitals) {
            boolean updated = false;
            if(v != null) {
                for (int x : v.getNames()) {
                    if (x == name) {
                        ListIterator<Value> li = v.getValues().listIterator();
                        while (li.hasNext()) {
                            Value va = li.next();
                            if (va.getUniversalDeviceIdentifier().equals(udi)) {
                                va.unregisterCriticalLimits(getWriter());
                                li.remove();
                                updated = true;
                            }
                        }
                    }
                }
                if (updated) {
                    fireVitalChanged(v);
                }
            }
        }
    }

    ThreadLocal<Vital[]> vitalBuffer = new ThreadLocal<Vital[]>() {
        @Override
        protected Vital[] initialValue() {
            return new Vital[0];
        }
    };

    private ice.AlarmSettingsObjectiveDataWriter writer;

    public ice.AlarmSettingsObjectiveDataWriter getWriter() {
        return writer;
    }

    protected void updateNumeric(Numeric n, SampleInfo si) {
        Vital[] vitals = vitalBuffer.get();
        vitals = this.vitals.toArray(vitals);
        vitalBuffer.set(vitals);
        // TODO linear search?  Query Condition should be vital specific
        // or maybe these should be hashed because creating myriad QueryConditions is not advisable
        for (Vital v : vitals) {
            if(v != null) {
                for (int x : v.getNames()) {
                    // Change to this vital from a source
                    if (x == n.name) {
                        boolean updated = false;
                        for (Value va : v.getValues()) {
                            if (va.getName() == n.name && va.getUniversalDeviceIdentifier().equals(n.universal_device_identifier)) {
                                va.updateFrom(n, si);
                                updated = true;
                            }
                        }
                        if (!updated) {
                            Value va = new ValueImpl(n.universal_device_identifier, n.name, v);
                            va.updateFrom(n, si);
                            v.getValues().add(va);
                            va.writeCriticalLimitsToDevice(getWriter());
                        }
                        fireVitalChanged(v);
                    }
                }
            }
        }
    }

    @Override
    public int getCount() {
        return vitals.size();
    }

    @Override
    public Vital getVital(int i) {
        return vitals.get(i);
    }

    @Override
    public Vital addVital(String label, String units, int[] names, Float low, Float high, Float criticalLow, Float criticalHigh, float minimum, float maximum, Long valueMsWarningLow, Long valueMsWarningHigh, Color color) {
        Vital v = new VitalImpl(this, label, units, names, low, high, criticalLow, criticalHigh, minimum, maximum, valueMsWarningLow, valueMsWarningHigh, color);
        vitals.add(v);
        addQueryConditions(v);
        fireVitalAdded(v);
        return v;
    }

    @Override
    public boolean removeVital(Vital vital) {
        boolean r = vitals.remove(vital);
        if (r) {
            removeQueryConditions(vital);
            fireVitalRemoved(vital);
        }
        return r;
    }

    @Override
    public Vital removeVital(int i) {
        Vital v = vitals.remove(i);
        if (v != null) {
            removeQueryConditions(v);
            fireVitalRemoved(v);
        }
        return v;
    }

    @Override
    public synchronized void addListener(VitalModelListener vitalModelListener) {
        VitalModelListener[] oldListeners = this.listeners;
        VitalModelListener[] newListeners = new VitalModelListener[oldListeners.length + 1];
        System.arraycopy(oldListeners, 0, newListeners, 0, oldListeners.length);
        newListeners[newListeners.length - 1] = vitalModelListener;
        this.listeners = newListeners;
    }

    @Override
    public synchronized boolean removeListener(VitalModelListener vitalModelListener) {
        VitalModelListener[] oldListeners = this.listeners;
        List<VitalModelListener> newListeners = new ArrayList<VitalModelListener>();
        boolean found = false;
        for (VitalModelListener vml : oldListeners) {
            if (vitalModelListener.equals(vml)) {
                found = true;
            } else {
                newListeners.add(vml);
            }
        }

        this.listeners = newListeners.toArray(new VitalModelListener[0]);
        return found;
    }

    protected void fireVitalAdded(Vital v) {
        updateState();
        VitalModelListener[] listeners = this.listeners;
        for (VitalModelListener vml : listeners) {
            vml.vitalAdded(this, v);
        }
    }

    protected void fireVitalRemoved(Vital v) {
        updateState();
        VitalModelListener[] listeners = this.listeners;
        for (VitalModelListener vml : listeners) {
            vml.vitalRemoved(this, v);
        }
    }

    protected void fireVitalChanged(Vital v) {
        updateState();
        VitalModelListener[] listeners = this.listeners;
        for (VitalModelListener vml : listeners) {
            vml.vitalChanged(this, v);
        }
    }
    public Device getDevice(String udi) {
        if(null == udi) {
            return null;
        }
        DeviceListModel deviceListModel = this.deviceListModel;
        return null == deviceListModel ? null : deviceListModel.getByUniversalDeviceIdentifier(udi);
    }
    public DeviceIcon getDeviceIcon(String udi) {
        Device device = getDevice(udi);
        return null == device ? null : device.getIcon();
    }

    @Override
    public DeviceIdentity getDeviceIdentity(String udi) {
        Device device = getDevice(udi);
        return null == device ? null : device.getDeviceIdentity();
    }

    @Override
    public DeviceConnectivity getDeviceConnectivity(String udi) {
        Device device = getDevice(udi);
        return null == device ? null : device.getDeviceConnectivity();
    }

    private void removeQueryConditions(final Vital v) {
        final NumericDataReader numericReader = this.numericReader;
        final EventLoop eventLoop = this.eventLoop;

        if (null != numericReader && null != eventLoop) {
            Set<QueryCondition> set = queryConditions.get(v);
            if (null != set) {
                for (QueryCondition qc : set) {
                    eventLoop.removeHandler(qc);
                    set.remove(qc);
                    numericReader.delete_readcondition(qc);
                }
                queryConditions.remove(v);
            }
        }
    }

    private void addQueryConditions(final Vital v) {
        final NumericDataReader numericReader = this.numericReader;
        final EventLoop eventLoop = this.eventLoop;

        if (null != numericReader && null != eventLoop) {
            // TODO this should probably be a ContentFilteredTopic to allow the
            // writer to do the filtering

            Set<QueryCondition> set = queryConditions.get(v);
            set = null == set ? new HashSet<QueryCondition>() : set;
            for (int x : v.getNames()) {
                StringSeq params = new StringSeq();
                params.add(Integer.toString(x));
                QueryCondition qc = numericReader.create_querycondition(SampleStateKind.NOT_READ_SAMPLE_STATE,
                        ViewStateKind.ANY_VIEW_STATE, InstanceStateKind.ANY_INSTANCE_STATE, "name = %0", params);
                set.add(qc);
                eventLoop.addHandler(qc, numericHandler);
            }
        }
    }

    @Override
    public void start(final Subscriber subscriber, final EventLoop eventLoop) {


        eventLoop.doLater(new Runnable() {
            public void run() {
                VitalModelImpl.this.subscriber = subscriber;
                VitalModelImpl.this.eventLoop = eventLoop;
                DomainParticipant participant = subscriber.get_participant();

                ice.AlarmSettingsObjectiveTypeSupport.register_type(participant, ice.AlarmSettingsObjectiveTypeSupport.get_type_name());
//                TopicDescription topic = TopicUtil.lookupOrCreateTopic(participant, ice.AlarmSettingsObjectiveTopic.VALUE, ice.AlarmSettingsObjectiveTypeSupport.class);
                Topic topic = participant.create_topic(ice.AlarmSettingsObjectiveTopic.VALUE, ice.AlarmSettingsObjectiveTypeSupport.get_type_name(), DomainParticipant.TOPIC_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
                writer = (AlarmSettingsObjectiveDataWriter) participant.create_datawriter(topic, Publisher.DATAWRITER_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);

                NumericTypeSupport.register_type(participant, NumericTypeSupport.get_type_name());
                TopicDescription nTopic = TopicUtil.lookupOrCreateTopic(participant, NumericTopic.VALUE,
                        NumericTypeSupport.class);
                numericReader = (NumericDataReader) subscriber.create_datareader(nTopic, Subscriber.DATAREADER_QOS_DEFAULT,
                        null, StatusKind.STATUS_MASK_NONE);

                for (Vital v : vitals) {
                    addQueryConditions(v);
                }

            }
        });
    }

    @Override
    public void stop() {
        eventLoop.doLater(new Runnable() {
            public void run() {
                subscriber.get_participant().delete_datawriter(writer);

                for (Vital v : queryConditions.keySet()) {
                    removeQueryConditions(v);
                }
                queryConditions.clear();
                numericReader.delete_contained_entities();
                subscriber.delete_datareader(numericReader);

                VitalModelImpl.this.subscriber = null;
                VitalModelImpl.this.eventLoop = null;
            }
        });

    }

    public static void main(String[] args) {
        DDS.init(false);
        DomainParticipant p = DomainParticipantFactory.get_instance().create_participant(0,
                DomainParticipantFactory.PARTICIPANT_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        Subscriber s = p.create_subscriber(DomainParticipant.SUBSCRIBER_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        VitalModel vm = new VitalModelImpl(null);

        vm.addListener(new VitalModelListener() {

            @Override
            public void vitalRemoved(VitalModel model, Vital vital) {
                System.out.println("Removed:" + vital);
            }

            @Override
            public void vitalChanged(VitalModel model, Vital vital) {
                System.out.println(new Date() + " Changed:" + vital);
            }

            @Override
            public void vitalAdded(VitalModel model, Vital vital) {
                System.out.println("Added:" + vital);
            }
        });
//        vm.addVital("Heart Rate", "bpm", new int[] { ice.MDC_PULS_OXIM_PULS_RATE.VALUE }, 20, 200, 10, 210, 0, 200);
        EventLoop eventLoop = new EventLoop();
        // EventLoopHandler eventLoopHandler =
        new EventLoopHandler(eventLoop);

        vm.start(s, eventLoop);
    }

    private static final String DEFAULT_INTERLOCK_TEXT = "Drug: Morphine\r\nRate: 4cc / hour";

    private static final DateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private String[] advisories = new String[0];
    private final Date now = new Date();
    private final StringBuilder warningTextBuilder = new StringBuilder();
    private String warningText = "", interlockText = DEFAULT_INTERLOCK_TEXT;
    private boolean interlock = false;

    // TODO I synchronized this because I saw a transient concurrent mod exception
    // but it's unclear to me which thread other than the EventLoopHandler should be calling it
    // am I mixing AWT and ELH calls?
    private final synchronized void updateState() {
        int N = getCount();

        while (advisories.length < N) {
            advisories = new String[2 * N + 1];
        }
        now.setTime(System.currentTimeMillis());
        String time = timeFormat.format(now);

        int countWarnings = 0;

        for (int i = 0; i < N; i++) {
            Vital vital = getVital(i);
            advisories[i] = null;
            if (vital.isNoValueWarning() && vital.getValues().isEmpty()) {
                countWarnings++;
                advisories[i] = "- no source of " + vital.getLabel() + "\r\n";
            } else {
                for (Value val : vital.getValues()) {
                    if (val.isAtOrBelowLow()) {
                        countWarnings++;
                        advisories[i] = "- low " + vital.getLabel() + " " + val.getNumeric().value + " "
                                + vital.getUnits() + "\r\n";
                    }
                    if (val.isAtOrAboveHigh()) {
                        countWarnings++;
                        advisories[i] = "- high " + vital.getLabel() + " " + val.getNumeric().value + " "
                                + vital.getUnits() + "\r\n";
                    }
                }
            }
        }

        // Advisory processing
        if (countWarnings>0) {
            warningTextBuilder.delete(0, warningTextBuilder.length());
            for (int i = 0; i < N; i++) {
                if (null != advisories[i]) {
                    warningTextBuilder.append(advisories[i]);
                }
            }
            warningTextBuilder.append("at ").append(time);
            warningText = warningTextBuilder.toString();
            state = State.Warning;
        } else {
            warningText = "";
            state = State.Normal;
        }

        if (countWarnings >= countWarningsBecomeAlarm) {
            state = State.Alarm;
            stopInfusion("Stopped\r\n" + warningText + "\r\nnurse alerted");
        } else {
            for (int i = 0; i < N; i++) {
                Vital vital = getVital(i);
                for (Value val : vital.getValues()) {
                    if (val.isAtOrBelowCriticalLow()) {
                        state = State.Alarm;
                        stopInfusion("Stopped\r\n- low " + vital.getLabel() + " " + val.getNumeric().value + " " + vital.getUnits() + "\r\nat " + time
                                + "\r\nnurse alerted");
                        break;
                    } else if (val.isAtOrAboveCriticalHigh()) {
                        state = State.Alarm;
                        stopInfusion("Stopped\r\n- high " + vital.getLabel() + " " +
                                + val.getNumeric().value + " " + vital.getUnits() + "\r\nat " + time
                                + "\r\nnurse alerted");
                        break;
                    }
                }
            }
        }


    }

    private final void stopInfusion(String str) {
        if (!interlock) {
            interlock = true;
            interlockText = str;
        }
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public String getInterlockText() {
        return interlockText;
    }

    @Override
    public String getWarningText() {
        return warningText;
    }

    @Override
    public void resetInfusion() {
        interlock = false;
        interlockText = DEFAULT_INTERLOCK_TEXT;
        fireVitalChanged(null);
    }

    @Override
    public boolean isInfusionStopped() {
        return interlock;
    }

    private int countWarningsBecomeAlarm = 2;

    @Override
    public void setCountWarningsBecomeAlarm(int countWarningsBecomeAlarm) {
        this.countWarningsBecomeAlarm = countWarningsBecomeAlarm;
        fireVitalChanged(null);
    }

    @Override
    public int getCountWarningsBecomeAlarm() {
        return countWarningsBecomeAlarm;
    }

    final DeviceListModel deviceListModel;
    public VitalModelImpl(DeviceListModel deviceListModel) {
        this.deviceListModel = deviceListModel;
    }
}
