package com.soundmatch.controller;

import static com.soundmatch.dto.ApiDtos.*;
import com.soundmatch.service.SoundMatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@ApiResponses({
    @ApiResponse(responseCode="400", description="Parámetros o audio inválidos", content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="404", description="Canción inexistente", content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="413", description="Archivo demasiado grande", content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="415", description="Audio no compatible", content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="500", description="Error interno", content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="503", description="IA no disponible", content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="504", description="Tiempo agotado", content=@Content(schema=@Schema(implementation=ApiError.class)))
})
public class SoundMatchController {
    private final SoundMatchService service;
    public SoundMatchController(SoundMatchService service) { this.service = service; }

    @GetMapping("/songs")
    @Operation(summary="Consultar el catálogo; páginas desde cero, sin embeddings")
    public SongPage songs(@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
                          @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
                          @RequestParam(required=false) @Size(max=200) String title,
                          @RequestParam(required=false) @Size(max=200) String artist,
                          @RequestParam(required=false) @Size(max=200) String genre) {
        return service.songs(page, size, title, artist, genre);
    }

    @GetMapping("/songs/{id}")
    public Song song(@PathVariable @Min(1) long id) { return service.song(id); }

    @GetMapping("/recommendations/{songId}")
    @Operation(summary="Recomendar usando el fragmento 0 y el ranking de la etapa 2")
    public Recommendations recommendations(@PathVariable @Min(1) long songId,
                                           @RequestParam(defaultValue="5") @Min(1) @Max(100) int limit) {
        return service.recommendations(songId, limit);
    }

    @PostMapping(value="/recommendations/audio", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary="Promediar ventanas de audio y recomendar con el ranking existente")
    public Recommendations audio(@RequestPart("file") MultipartFile file,
                                  @RequestParam(defaultValue="5") @Min(1) @Max(100) int limit) {
        return service.audio(file, limit);
    }
}
