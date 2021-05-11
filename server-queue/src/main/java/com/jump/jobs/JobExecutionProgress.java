package com.jump.jobs;

import com.jump.jobs.configs.CustomJdbcJobExecutionDao;
import com.jump.jobs.configs.JobExecutionService;
import com.jump.objects.asset.Asset;
import com.jump.objects.jobObject.JobEvent;
import com.jump.objects.jobObject.JobEventService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.messaging.Sink;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@EnableBinding({Source.class, Sink.class})
@Slf4j
public class JobExecutionProgress {
    @Autowired
    private JobExplorer jobExplorer;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JdbcOperations jdbcOperations;
    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    private JobRegistry jobRegistry;
    @Autowired
    private JobEventService jobEventService;
    @Autowired
    private Source source;

    @SneakyThrows
    @StreamListener(target = Sink.INPUT, condition = "headers['custom_info']=='end'")
    public void receiveResult(final Message<JobEvent> parMsg) {
        final JobEvent locJobEvent = parMsg.getPayload();
        final int locWorkerPartition = (int)parMsg.getHeaders().get(KafkaHeaders.RECEIVED_PARTITION_ID);
        log.info("[Server END] received result, partition SAVE : {} | partition WORKER {}", jobEventService.getJobEventById(locJobEvent.getJobId()), locWorkerPartition);

        afterReceiveMsg(locJobEvent, locWorkerPartition);
    }

    @SneakyThrows
    @StreamListener(target = Sink.INPUT, condition = "headers['custom_info']=='infos'")
    public void receiveInfos(final Message<JobEvent> parMsg) {
        final JobEvent locJobEvent = parMsg.getPayload();
        final int parWorkerPartition = (int)parMsg.getHeaders().get("worker_partition");
        log.info("[Server INFOS] received infos, from partition {}", parWorkerPartition);

        // save the jobEvent
        locJobEvent.setChannelPartition(parWorkerPartition);
        final JobEvent locNewJobEvent = jobEventService.save(locJobEvent);
        final Message<JobEvent> locMsg = MessageBuilder.withPayload(locNewJobEvent)
                                                       .setHeader("custom_info", "processing")
                                                       .setHeader(KafkaHeaders.PARTITION_ID, locNewJobEvent.getChannelPartition())
                                                       .build();
        source.output().send(locMsg);
    }

    @SneakyThrows
    private void afterReceiveMsg(final JobEvent parJobEvent, final Integer parPartition) {
        log.info("[Server] I received a message. {} from partition {}", parJobEvent, parPartition);
        final JobExecution locJobExecution = jobExplorer.getJobExecution(parJobEvent.getJobExecutionId());
        assert locJobExecution != null;
        if (locJobExecution.getExitStatus().equals(ExitStatus.COMPLETED)) {
            return;
        }
        locJobExecution.setExitStatus(new ExitStatus(parJobEvent.getExitStatus()));
        jobRepository.update(locJobExecution);
        log.info("[Server] Update status JobExecution : {}", locJobExecution);

        // start next job (if present) with same name & parameters

        final CustomJdbcJobExecutionDao locCustomJdbcJobExecutionDao = new CustomJdbcJobExecutionDao(jdbcOperations);
        final List<JobExecution> locLatestRunningJobs = locCustomJdbcJobExecutionDao.getRunningJobExecutionsWithParams(
                locJobExecution.getId(),
                locJobExecution.getJobInstance().getJobName(),
                locJobExecution.getJobParameters());

        if (!locLatestRunningJobs.isEmpty()) {
            final JobExecutionService locJobExecutionService = new JobExecutionService(jobRepository, jobRegistry, jobLauncher);
            final JobExecution locLatestRunningJob = locJobExecutionService.getFirstJobExecutioin(locLatestRunningJobs, null);
            final Long locNewJobExecutionId = locJobExecutionService.restartJob(locLatestRunningJob);
            log.info("[Server] create new JobExecution with id : {}", locNewJobExecutionId);
        }
    }
}