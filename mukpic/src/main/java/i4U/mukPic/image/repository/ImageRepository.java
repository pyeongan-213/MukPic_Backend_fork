package i4U.mukPic.image.repository;


import i4U.mukPic.image.entity.Image;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImageRepository extends JpaRepository<Image, Long> {

    // referenceId와 imageType으로 이미지를 조회하는 메소드
    List<Image> findByReferenceIdAndImageType(Short referenceId, Short imageType);

    Optional<Image> findByImageTypeAndReferenceId (Short imageType, Short referenceId);

    Optional<Image> findByImageUrl (String imageUrl);

    Optional<Image> findByReferenceId (Short referenceId);
}
