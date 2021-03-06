package com.numble.shortForm.video.service;

import com.numble.shortForm.exception.CustomException;
import com.numble.shortForm.exception.ErrorCode;
import com.numble.shortForm.hashtag.entity.HashTag;
import com.numble.shortForm.hashtag.entity.VideoHash;
import com.numble.shortForm.hashtag.repository.VideoHashRepository;
import com.numble.shortForm.hashtag.service.HashTagService;
import com.numble.shortForm.security.AuthenticationFacade;
import com.numble.shortForm.upload.S3Uploader;
import com.numble.shortForm.user.entity.Users;
import com.numble.shortForm.user.repository.UsersRepository;
import com.numble.shortForm.video.dto.request.EmbeddedVideoRequestDto;
import com.numble.shortForm.video.dto.response.VideoResponseDto;
import com.numble.shortForm.video.entity.*;
import com.numble.shortForm.video.repository.RecordVideoRepository;
import com.numble.shortForm.video.repository.VideoLikeRepository;
import com.numble.shortForm.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class VideoService {

    private final VideoRepository videoRepository;
    private final VideoLikeRepository videoLikeRepository;
    private final S3Uploader s3Uploader;
    private final UsersRepository usersRepository;
    private final HashTagService hashTagService;
    private final VideoHashRepository videoHashRepository;
    private final RedisTemplate redisTemplate;
    private final RecordVideoRepository recordVideoRepository;
    private final RecordVideoService recordVideoService;
    private final AuthenticationFacade authenticationFacade;

    private static final int PAGE_SIZE =5;
    //embedded ?????? ?????????
    public void uploadEmbeddedVideo(EmbeddedVideoRequestDto embeddedVideoRequestDto, Long usersId) throws IOException {

        Users users = usersRepository.findById(usersId).orElseThrow(()->{
            throw new CustomException(ErrorCode.NOT_FOUND_USER,"????????? ???????????? ????????????.");
        });

        Thumbnail thumbnail;

        if (embeddedVideoRequestDto.getThumbNail() != null) {
            String url = s3Uploader.uploadFile(embeddedVideoRequestDto.getThumbNail(),"thumbnail");
            thumbnail = new Thumbnail(url,embeddedVideoRequestDto.getThumbNail().getOriginalFilename());
        }else{
            thumbnail = new Thumbnail(null,null);
        }

        Video video = Video.builder()
                .videoUrl(embeddedVideoRequestDto.getVideoUrl())
                .thumbnail(thumbnail)
                .title(embeddedVideoRequestDto.getTitle())
                .description(embeddedVideoRequestDto.getDescription())
                .videoType(VideoType.embedded)
                .isBlock(false)
                .users(users)
                .duration(embeddedVideoRequestDto.getDuration())
                .build();
        Video createdVideo = videoRepository.save(video);

        if (embeddedVideoRequestDto.getTags().isEmpty()) {
            return;
        }

        List<HashTag> tags = hashTagService.createTag(embeddedVideoRequestDto.getTags());

        List<VideoHash> videoHashes = tags.stream().map(t -> new VideoHash(createdVideo, t))
                .collect(Collectors.toList());

        createdVideo.addVideoHash(videoHashes);
        videoRepository.save(createdVideo);

    }

    public Page<VideoResponseDto> retrieveAll(Pageable pageable) {
        return videoRepository.retrieveAll(pageable);
    }

    //????????????????????? ???????????????
    public VideoResponseDto retrieveDetailNotLogin(Long videoId, String ip) {
        String IsExistRedis = (String) redisTemplate.opsForValue().get(videoId + "/" + ip);
        if (IsExistRedis == null) {
            videoRepository.updateView(videoId);
            redisTemplate.opsForValue().set(videoId+"/"+ip,"Anonymous User",5L,TimeUnit.MINUTES);
        }

        VideoResponseDto videoResponseDto = videoRepository.retrieveDetail(videoId);
        List<String> tags = videoHashRepository.findAllByVideoId(videoId).stream().map(h ->h.getHashTag().getTagName())
                .collect(Collectors.toList());

        videoResponseDto.setTags(tags);
        videoResponseDto.setLiked(false);
        return videoResponseDto;
    }
    // ????????? ????????????(?????????)
    public VideoResponseDto retrieveDetail(Long videoId,String ip,Long userId) {


        // Redis??? 5????????? ?????? ip????????? ????????? ??????
        String IsExistRedis = (String) redisTemplate.opsForValue().get(videoId + "/" + ip);
        if (IsExistRedis == null) {
            videoRepository.updateView(videoId);
            redisTemplate.opsForValue().set(videoId+"/"+ip,"true",5L,TimeUnit.MINUTES);
        }


        VideoResponseDto videoResponseDto = videoRepository.retrieveDetail(videoId);

         List<String> tags = videoHashRepository.findAllByVideoId(videoId).stream().map(h ->h.getHashTag().getTagName())
                 .collect(Collectors.toList());

         videoResponseDto.setTags(tags);
         //????????? ???????????? ??????
        if (searchVideoLike(userId, videoId) != null) {
            videoResponseDto.setLiked(true);
        }
        videoResponseDto.setLiked(false);
         // ?????? ??????

        recordVideoRepository.save(new RecordVideo(videoId,userId));

        return videoResponseDto;
    }


    // ????????????????????? ??????
    public Page<VideoResponseDto> retrieveMyVideo(String userEmail, Pageable pageable) {
        return videoRepository.retrieveMyVideo(userEmail,pageable);
    }

    // ????????? ??????
    public boolean requestLikeVideo(String userEmail,Long videoId) {
        Users users = usersRepository.findByEmail(userEmail).orElseThrow(()-> new CustomException(ErrorCode.NOT_FOUND_USER));
        Video video = videoRepository.findById(videoId).orElseThrow(()-> new CustomException(ErrorCode.NOT_FOUND_VIDEO,String.format("[%d] ????????? ???????????? ???????????? ????????????.",videoId)));

        if (existsVideoLike(users, video)) {
            videoLikeRepository.save(new VideoLike(users,video));
            return true;
        }
        videoLikeRepository.deleteByUsersAndVideo(users,video);
        return false;
    }

    private boolean existsVideoLike(Users users, Video video) {
       return videoLikeRepository.findByUsersAndVideo(users,video).isEmpty();
    }

    //?????? ????????? ??????
    public Page<VideoResponseDto> retrieveConcernVideos(Pageable pageable,Long userId,Long videoId) {

        Users users = usersRepository.findById(userId).orElseThrow(()-> new CustomException(ErrorCode.NOT_FOUND_USER));

        //???????????? ?????? ????????? id ????????? ????????? 5???
        List<Long> recordVideoList = recordVideoService.getRecordVideoList(videoId, users.getId(),PageRequest.of(0,PAGE_SIZE, Sort.by("created_at").descending()));


//        for (Long aLong : recordVideoList) {
//            Video video = videoRepository.findById(aLong).orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_VIDEO, "db?????? ??????????????? ???????????????"));
//            List<VideoHash> videoHashes = video.getVideoHashes();
//            for (VideoHash videoHash : videoHashes) {
//                System.out.println(videoHash.getHashTag().getTagName());
//            }
//        }
//
//        hashTagService.getTagByConcern(recordVideoList);
//
//        videoRepository.getVideoByTag(videoId);


        return null;
    }
    // ??????????????? ?????? ????????????
    public Page<VideoResponseDto> retrieveConcernVideosNotLogin(Pageable pageable,Long videoId) {

        // videoid ??? tag id ??????
        List<Long> tagids = videoHashRepository.findAllByVideoId(videoId).stream().map(obj -> obj.getHashTag().getId()).collect(Collectors.toList());
        // tag?????? ????????? tag id??? ?????? video id ??????
        List<Long> videoids = videoHashRepository.findAllByHashTagIdIn(tagids).stream().map(obj -> obj.getVideo().getId()).collect(Collectors.toList());
        // ?????? ????????? ??????
        videoids.remove(videoId);


        return videoRepository.retrieveConcernVideo(videoids,pageable);
    }

        // ????????? ??????
    public Page<VideoResponseDto> searchVideoQuery(String query,Pageable pageable) {
        return videoRepository.searchVideoQuery(query,pageable);
    }

    //???????????? ?????? ????????? ?????????
    public Page<VideoResponseDto> retrieveMainVideoList(Pageable pageable,Long userId) {
        Page<VideoResponseDto> videoResponseDtos = videoRepository.retrieveMainVideo(pageable);

        Users users = usersRepository.findById(userId).orElseThrow(()->
                new CustomException(ErrorCode.NOT_FOUND_USER));

        for (VideoResponseDto videoResponseDto : videoResponseDtos) {

            //????????? get?????? ????????????!!!!
            Video video = videoRepository.findById(videoResponseDto.getVideoId()).get();

            if (videoLikeRepository.existsByVideoAndUsers(video, users)) {
                log.info("likes ?????????!");
                videoResponseDto.setLiked(true);
                continue;
            }
            videoResponseDto.setLiked(false);
        }


        return videoResponseDtos;

    }

    //????????????????????? ?????? ????????? ?????????
    public Page<VideoResponseDto> retrieveMainVideoListNotLogin(Pageable pageable) {

        return  videoRepository.retrieveMainVideoNotLogin(pageable);

    }

    private VideoLike searchVideoLike(Long usersId, Long videoId) {

        Users users = usersRepository.getById(usersId);
        Video video = videoRepository.getById(videoId);
        return  videoLikeRepository.findByUsersAndVideo(users,video).orElse(null);
    }
}
